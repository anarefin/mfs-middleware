package mfs.mw.simulators.service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import mfs.mw.simulators.config.SimulatorProperties;
import mfs.mw.simulators.dto.SimMode;
import mfs.mw.simulators.dto.SmscDlrState;
import mfs.mw.simulators.dto.SmscMessage;
import mfs.mw.simulators.exception.MockAbortException;
import mfs.mw.simulators.exception.SmscRefusalException;
import mfs.mw.simulators.service.mockcontrol.Decision;
import mfs.mw.simulators.service.mockcontrol.Journal;
import mfs.mw.simulators.service.mockcontrol.MagicValuesOnly;
import mfs.mw.simulators.service.mockcontrol.MockCalls;
import mfs.mw.simulators.service.mockcontrol.MockControl;
import mfs.mw.simulators.service.mockcontrol.MockParties;
import mfs.mw.simulators.service.mockcontrol.PartyState;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * SMSC simulator logic (spec 22.2, ticket 24), independent of the SMPP plumbing ({@code
 * client/SmscServer}). Every {@code submit_sm} gets a message_id ({@code SMSC-<start>-<n>}); text is decoded from GSM-7
 * (data_coding 0, one septet per byte) or UCS-2 (8), and parts carrying a concatenation UDH
 * (esm_class UDHI, IE 0x00 or 0x08) are reassembled. Destination magic suffixes: {@code 999} DLR
 * UNDELIV, {@code 998} DLR EXPIRED, {@code 997} the first two submits refused with
 * ESME_RTHROTTLED (0x58), {@code 996} refused with ESME_RINVDSTADR (0x0B), {@code 995} no DLR;
 * anything else DLR DELIVRD. The magic values are the default of the mock party {@code smsc} (ticket
 * 102b, ADR D33, FRS MCT-04): a scenario on {@code bind} or {@code submit} wins over them. State is in
 * memory, last {@code sim.record-limit} messages; the party reset clears them.
 */
@Service
public class SmscService implements PartyState {

  /** An accepted part: its message_id, the receipt to send (null = none) and the mock-control decision. */
  public record Submission(String messageId, SmscDlrState dlrState, Decision decision) {}

  /** The mock party (ticket 102b, FRS MCT-04): operations bind and submit; the DLR is its callback. */
  public static final String PARTY = "smsc";

  private static final int STAT_THROTTLED = 0x58;
  private static final int STAT_INVALID_DEST = 0x0B;
  private static final int STAT_BIND_FAILED = 0x0D;
  private static final int STAT_SYSTEM_ERROR = 0x08;
  private static final int THROTTLED_SUBMITS = 2;

  private static final String GSM_BASIC =
      "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞ\u001bÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?"
          + "¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà";

  private static final Map<Integer, Character> GSM_EXTENSION =
      Map.of(0x0A, '\f', 0x14, '^', 0x28, '{', 0x29, '}', 0x2F, '\\', 0x3C, '[', 0x3D, '~', 0x3E, ']',
          0x40, '|', 0x65, '€');

  private record Part(String messageId, int seq, byte[] body) {}

  private record Pending(String destination, String source, String encoding, int total, List<Part> parts) {}

  private final int recordLimit;
  private final AtomicInteger ids = new AtomicInteger();
  /**
   * Start of this instance (base 36 millis) in every message_id: a restarted simulator never repeats
   * an id of an earlier run, which mw-notify keeps unique per provider.
   */
  private final String run;
  private final Clock clock;
  private final List<SmscMessage> messages = new ArrayList<>();
  private final Map<String, Pending> pending = new LinkedHashMap<>();
  private final Map<String, SmscDlrState> dlrs = new ConcurrentHashMap<>();
  private final Map<String, AtomicInteger> throttled = new ConcurrentHashMap<>();

  private final MockControl control;
  private final ObjectProvider<Journal> journal;

  @Autowired
  public SmscService(SimulatorProperties props, Clock clock, MockControl control, ObjectProvider<Journal> journal) {
    this.recordLimit = props.recordLimit();
    this.clock = clock;
    this.run = Long.toString(clock.millis(), 36);
    this.control = control;
    this.journal = journal;
  }

  /** Without mock control: the magic values alone. */
  SmscService(int recordLimit, Clock clock) {
    this(new SimulatorProperties(Duration.ofSeconds(30), recordLimit), clock,
        new MagicValuesOnly(new SimulatorProperties(Duration.ofSeconds(30), recordLimit)), null);
  }

  /**
   * A bind of {@code systemId} (operation {@code bind}): empty = accept; a status = refuse the bind with
   * it (BUSINESS_ERROR, default ESME_RBINDFAIL 0x0D; MAINTENANCE 0x0D). {@link MockAbortException} = close
   * the connection unanswered (DOWN, CONNECTION_RESET).
   */
  public OptionalInt bind(String systemId) {
    String id = systemId == null ? "" : systemId;
    Decision d = control.decide(PARTY, "bind", Map.of("systemId", id), SimMode.SUCCESS);
    record("bind", Map.of("systemId", id));
    MockCalls.pause(d.delayMs());
    if (d.gate() == Decision.Gate.DOWN || d.mode() == SimMode.CONNECTION_RESET) {
      throw new MockAbortException(PARTY);
    }
    if (d.gate() == Decision.Gate.MAINTENANCE) {
      return OptionalInt.of(STAT_BIND_FAILED);
    }
    if (d.mode() == SimMode.BUSINESS_FAILURE) {
      return OptionalInt.of(d.code() == null ? STAT_BIND_FAILED : MockParties.smppStatus(d.code()));
    }
    return OptionalInt.empty();
  }

  /**
   * One {@code submit_sm} (operation {@code submit}, key {@code msisdn} = the destination). Refused
   * (nothing stored) with a {@link SmscRefusalException}: BUSINESS_ERROR (its code, default the magic
   * value's, else ESME_RTHROTTLED 0x58), MAINTENANCE (ESME_RSYSERR 0x08) and the magic suffixes 996 / 997.
   * DOWN throws {@link MockAbortException} (nothing stored). Otherwise stored; the returned decision says
   * what the transport still does (TIMEOUT hold, HTTP_5XX = ESME_RSYSERR after storing, CONNECTION_RESET,
   * the DLR: none, once, twice, late, out of order).
   */
  public synchronized Submission submit(String destination, String source, byte dataCoding, byte esmClass,
      byte[] shortMessage) throws SmscRefusalException {
    String dest = destination == null ? "" : destination;
    boolean magicThrottle = dest.endsWith("997")
        && throttled.computeIfAbsent(dest, d -> new AtomicInteger()).get() < THROTTLED_SUBMITS;
    Integer magicCode = dest.endsWith("996") ? Integer.valueOf(STAT_INVALID_DEST) : magicThrottle ? STAT_THROTTLED : null;
    Decision d = control.decide(PARTY, "submit", Map.of("msisdn", dest),
        magicCode == null ? SimMode.SUCCESS : SimMode.BUSINESS_FAILURE);
    record("submit_sm", Map.of("destination", dest, "source", source == null ? "" : source));
    if (d.gate() == Decision.Gate.DOWN) {
      throw new MockAbortException(PARTY);
    }
    if (d.gate() == Decision.Gate.MAINTENANCE) {
      throw new SmscRefusalException(STAT_SYSTEM_ERROR, "maintenance (mock control)");
    }
    if (d.mode() == SimMode.BUSINESS_FAILURE) {
      if (d.code() != null) {
        throw new SmscRefusalException(MockParties.smppStatus(d.code()), "refused (mock control)");
      }
      if ("magic".equals(d.source()) && magicCode != null) {
        if (magicThrottle) {
          throttled.get(dest).incrementAndGet();
        }
        throw new SmscRefusalException(magicCode, "refused (magic " + dest.substring(dest.length() - 3) + ")");
      }
      throw new SmscRefusalException(STAT_THROTTLED, "throttled (mock control)");
    }
    String id = "SMSC-" + run + "-" + ids.incrementAndGet();
    String encoding = dataCoding == 0x08 ? "UCS2" : "GSM7";
    byte[] sm = shortMessage == null ? new byte[0] : shortMessage;
    boolean udhi = (esmClass & 0x40) != 0 && sm.length > 0;
    int[] concat = udhi ? concatInfo(sm) : null;
    byte[] body = udhi ? Arrays.copyOfRange(sm, Math.min(sm.length, (sm[0] & 0xFF) + 1), sm.length) : sm;
    if (concat == null || concat[1] <= 1) {
      store(dest, source, encoding, List.of(new Part(id, 1, body)));
    } else {
      String key = dest + "|" + concat[0] + "|" + concat[1];
      Pending p = pending.computeIfAbsent(key, k -> new Pending(dest, source, encoding, concat[1], new ArrayList<>()));
      p.parts().add(new Part(id, concat[2], body));
      if (p.parts().size() >= p.total()) {
        pending.remove(key);
        store(dest, source, encoding, p.parts());
      }
    }
    SmscDlrState dlr = switch (d.callback()) {
      case NONE -> null;
      case ONCE -> dlrFor(dest);
      default -> dlrFor(dest) == null ? SmscDlrState.DELIVRD : dlrFor(dest);
    };
    return new Submission(id, dlr, d);
  }

  @Override
  public void reset(String party) {
    if (PARTY.equals(party)) {
      reset();
    }
  }

  private void record(String what, Map<String, String> headers) {
    if (journal != null) {
      journal.ifAvailable(j -> j.record(PARTY, "SMPP", what, null, headers, null, clock.instant()));
    }
  }

  /** Records the receipt sent for a part. */
  public void dlrSent(String messageId, SmscDlrState state) {
    dlrs.merge(messageId, state, (was, now) -> was.compareTo(now) >= 0 ? was : now);
  }

  /** Reassembled messages, oldest first; to one MSISDN when {@code msisdn} is given. */
  public synchronized List<SmscMessage> messages(String msisdn) {
    return messages.stream()
        .filter(m -> msisdn == null || msisdn.isBlank() || m.destination().equals(msisdn))
        .map(m -> new SmscMessage(m.destination(), m.source(), m.text(), m.encoding(), m.segments(), m.messageIds(),
            dlrStatus(m.messageIds()), m.receivedAt()))
        .toList();
  }

  public synchronized void reset() {
    messages.clear();
    pending.clear();
    dlrs.clear();
    throttled.clear();
  }

  private void store(String dest, String source, String encoding, List<Part> parts) {
    List<Part> ordered = parts.stream().sorted(Comparator.comparingInt(Part::seq)).toList();
    ByteArrayOutputStream all = new ByteArrayOutputStream();
    ordered.forEach(p -> all.writeBytes(p.body()));
    String text = decode(encoding, all.toByteArray());
    messages.add(new SmscMessage(dest, source, text, encoding, ordered.size(),
        ordered.stream().map(Part::messageId).toList(), null, clock.instant()));
    while (messages.size() > recordLimit) {
      messages.removeFirst();
    }
  }

  private String dlrStatus(List<String> messageIds) {
    return messageIds.stream()
        .map(dlrs::get)
        .filter(Objects::nonNull)
        .max(Comparator.naturalOrder())
        .map(Enum::name)
        .orElse(null);
  }

  private static SmscDlrState dlrFor(String dest) {
    if (dest.endsWith("999")) {
      return SmscDlrState.UNDELIV;
    }
    if (dest.endsWith("998")) {
      return SmscDlrState.EXPIRED;
    }
    if (dest.endsWith("995")) {
      return null;
    }
    return SmscDlrState.DELIVRD;
  }

  /** {ref, total, seq} from a concatenation IE (0x00 8-bit or 0x08 16-bit ref), or null. */
  private static int[] concatInfo(byte[] sm) {
    int udhl = sm[0] & 0xFF;
    int i = 1;
    while (i + 1 < sm.length && i <= udhl) {
      int iei = sm[i] & 0xFF;
      int len = sm[i + 1] & 0xFF;
      if (iei == 0x00 && len == 3 && i + 4 < sm.length) {
        return new int[] {sm[i + 2] & 0xFF, sm[i + 3] & 0xFF, sm[i + 4] & 0xFF};
      }
      if (iei == 0x08 && len == 4 && i + 5 < sm.length) {
        return new int[] {((sm[i + 2] & 0xFF) << 8) | (sm[i + 3] & 0xFF), sm[i + 4] & 0xFF, sm[i + 5] & 0xFF};
      }
      i += 2 + len;
    }
    return null;
  }

  static String decode(String encoding, byte[] bytes) {
    if ("UCS2".equals(encoding)) {
      return new String(bytes, StandardCharsets.UTF_16BE);
    }
    StringBuilder out = new StringBuilder(bytes.length);
    for (int i = 0; i < bytes.length; i++) {
      int b = bytes[i] & 0x7F;
      if (b == 0x1B && i + 1 < bytes.length) {
        Character ext = GSM_EXTENSION.get(bytes[++i] & 0x7F);
        out.append(ext == null ? ' ' : ext);
      } else {
        out.append(GSM_BASIC.charAt(b));
      }
    }
    return out.toString();
  }
}
