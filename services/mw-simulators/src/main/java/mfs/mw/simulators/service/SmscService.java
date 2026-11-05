package mfs.mw.simulators.service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import mfs.mw.simulators.config.SimulatorProperties;
import mfs.mw.simulators.dto.SmscDlrState;
import mfs.mw.simulators.dto.SmscMessage;
import mfs.mw.simulators.exception.SmscRefusalException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * SMSC simulator logic (spec 22.2, ticket 24), independent of the SMPP plumbing ({@code
 * client/SmscServer}). Every {@code submit_sm} gets a message_id; text is decoded from GSM-7
 * (data_coding 0, one septet per byte) or UCS-2 (8), and parts carrying a concatenation UDH
 * (esm_class UDHI, IE 0x00 or 0x08) are reassembled. Destination magic suffixes: {@code 999} DLR
 * UNDELIV, {@code 998} DLR EXPIRED, {@code 997} the first two submits refused with
 * ESME_RTHROTTLED (0x58), {@code 996} refused with ESME_RINVDSTADR (0x0B), {@code 995} no DLR;
 * anything else DLR DELIVRD. State is in memory, last {@code sim.record-limit} messages.
 */
@Service
public class SmscService {

  /** An accepted part: its message_id and the receipt to send (null = none). */
  public record Submission(String messageId, SmscDlrState dlrState) {}

  private static final int STAT_THROTTLED = 0x58;
  private static final int STAT_INVALID_DEST = 0x0B;
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
  private final List<SmscMessage> messages = new ArrayList<>();
  private final Map<String, Pending> pending = new LinkedHashMap<>();
  private final Map<String, SmscDlrState> dlrs = new ConcurrentHashMap<>();
  private final Map<String, AtomicInteger> throttled = new ConcurrentHashMap<>();

  @Autowired
  public SmscService(SimulatorProperties props) {
    this(props.recordLimit());
  }

  SmscService(int recordLimit) {
    this.recordLimit = recordLimit;
  }

  public synchronized Submission submit(String destination, String source, byte dataCoding, byte esmClass,
      byte[] shortMessage) throws SmscRefusalException {
    String dest = destination == null ? "" : destination;
    if (dest.endsWith("996")) {
      throw new SmscRefusalException(STAT_INVALID_DEST, "invalid destination (magic 996)");
    }
    if (dest.endsWith("997")
        && throttled.computeIfAbsent(dest, d -> new AtomicInteger()).getAndIncrement() < THROTTLED_SUBMITS) {
      throw new SmscRefusalException(STAT_THROTTLED, "throttled (magic 997)");
    }
    String id = "SMSC-" + ids.incrementAndGet();
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
    return new Submission(id, dlrFor(dest));
  }

  /** Records the receipt sent for a part. */
  public void dlrSent(String messageId, SmscDlrState state) {
    dlrs.put(messageId, state);
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
        ordered.stream().map(Part::messageId).toList(), null, Instant.now()));
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
