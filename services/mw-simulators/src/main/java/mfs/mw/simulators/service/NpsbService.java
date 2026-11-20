package mfs.mw.simulators.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import mfs.mw.simulators.client.NpsbInboundClient;
import mfs.mw.simulators.dto.CallbackMode;
import mfs.mw.simulators.dto.NpsbParticipant;
import mfs.mw.simulators.dto.SimMode;
import mfs.mw.simulators.dto.SimReply;
import mfs.mw.simulators.service.mockcontrol.Decision;
import mfs.mw.simulators.service.mockcontrol.MockCalls;
import mfs.mw.simulators.service.mockcontrol.MockControl;
import mfs.mw.simulators.service.mockcontrol.PartyState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * NPSB switch stand-in (tickets 121a and 121b part 2, FRS EXT-13, MCK-01..07; mock party {@code npsb}).
 *
 * <ul>
 *   <li>Participants of the versioned seed {@code seed/v1/npsb/participants.json} ({@code GET
 *       /npsb/v1/participants}).
 *   <li>Stateful transfers, the wire of mw-adapter-interop's {@code npsb} seed: {@code POST /npsb/v1/transfers |
 *       status | returns {messageType, document}} -> 200 {@code {msgId, document}} with the pacs.002 /
 *       camt.029. One transfer per EndToEndId (a repeat answers its current state, nothing applied twice);
 *       pacs.028 answers the stored state (404 unknown); a PDNG transfer settles after {@code
 *       sim.npsb.settle-delay}; a transfer is returned once (CNCL, then RJCR).
 *   <li>The inbound push {@code POST /mock-control/npsb/inbound}: a pacs.008 to {@code sim.npsb.inbound-url}
 *       (once, twice, late or not at all by the callback dimension of operation {@code inbound}).
 * </ul>
 *
 * Data triggers (creditor account's last three digits; the defaults mock control overrides): see the
 * module CLAUDE.md. What the mock sends is signed with the dev switch key ({@link NpsbSigner}).
 */
@Service
public class NpsbService implements PartyState {

  public static final String PARTNER = "npsb";
  static final String OWN_BIC = "NPSBBDDH";

  static final List<NpsbParticipant> PARTICIPANTS = SeedFiles.npsbParticipants(SeedFiles.VERSION);

  private static final Logger log = LoggerFactory.getLogger(NpsbService.class);

  /** One transfer the switch holds. {@code settleTo} is the final state a PDNG transfer reaches. */
  record Transfer(NpsbIsoXml.Transfer in, Instant at, String status, String reason, String settleTo,
      String settleReason, boolean returned, String clearingRef) {

    Transfer settled(Instant now, Duration delay) {
      if (!"PDNG".equals(status) || now.isBefore(at.plus(delay))) {
        return this;
      }
      return new Transfer(in, at, settleTo, settleReason, null, null, returned, clearingRef);
    }
  }

  /** The inbound push request (test helper). */
  public record InboundPush(String creditorAccount, Long amountMinor, String endToEndId, String creditorAgent,
      String debtorAgent, Integer ageMinutes) {}

  /** What the push did: the EndToEndId and the middleware's answers (status, body), one per copy sent. */
  public record InboundResult(String endToEndId, List<NpsbInboundClient.Answer> answers) {}

  private final MockControl control;
  private final NpsbInboundClient client;
  private final NpsbSigner signer;
  private final Clock clock;
  private final Duration settleDelay;
  private final Duration holdDelay;
  private final String inboundUrl;
  private final Map<String, Transfer> transfers = new ConcurrentHashMap<>();
  private final AtomicLong refs = new AtomicLong();

  public NpsbService(
      MockControl control,
      NpsbInboundClient client,
      NpsbSigner signer,
      Clock clock,
      @Value("${sim.npsb.settle-delay:5s}") Duration settleDelay,
      @Value("${sim.timeout-delay:30s}") Duration holdDelay,
      @Value("${sim.npsb.inbound-url:http://mw-adapter-interop:8080/partner/v1/npsb/inbound}") String inboundUrl) {
    this.control = control;
    this.client = client;
    this.signer = signer;
    this.clock = clock;
    this.settleDelay = settleDelay;
    this.holdDelay = holdDelay;
    this.inboundUrl = inboundUrl;
  }

  /** The participants, as mock control decides (a BUSINESS_ERROR refuses with its code). */
  public SimReply participants() {
    Decision d = control.decide(PARTNER, "participants", Map.of(), SimMode.SUCCESS);
    return MockCalls.answer(
        d,
        PARTNER,
        () -> new SimReply(200, Map.of("participants", PARTICIPANTS)),
        () -> new SimReply(503, Map.of("status", "ERROR")),
        () -> new SimReply(500, Map.of("status", "ERROR")),
        code -> new SimReply(422, Map.of("status", "FAILED", "code", code == null ? "NARR" : code)));
  }

  /** A pacs.008: stored once per EndToEndId and answered with its pacs.002. */
  public SimReply transfer(String document) {
    NpsbIsoXml.Transfer in;
    try {
      in = NpsbIsoXml.readPacs008(document);
    } catch (IllegalArgumentException e) {
      return new SimReply(400, Map.of("code", "FF01", "message", "not a pacs.008"));
    }
    Decision d = control.decide(PARTNER, "transfer",
        Map.of("participant", nz(in.creditorAgent()), "txnRef", nz(in.endToEndId())), SimMode.SUCCESS);
    NpsbIsoXml.Transfer msg = in;
    return MockCalls.answer(
        d,
        PARTNER,
        () -> {
          Transfer t = transfers.computeIfAbsent(msg.endToEndId(), k -> book(msg));
          if (t.reason() == null && t.status().equals("ACSC") && "994".equals(suffix(msg.creditorAccount()))
              && t.at().plus(Duration.ofSeconds(1)).isAfter(clock.instant())) {
            // trigger 994: applied, the answer held past the adapter's time limit (first call only)
            MockCalls.pause(holdDelay.toMillis());
          }
          return status(current(msg.endToEndId()));
        },
        () -> new SimReply(503, Map.of("code", "5030", "message", "maintenance")),
        () -> new SimReply(500, Map.of("code", "5000", "message", "error")),
        code -> {
          Transfer t = transfers.computeIfAbsent(msg.endToEndId(), k -> new Transfer(msg, clock.instant(), "RJCT",
              code == null ? "NARR" : code, null, null, false, null));
          return status(t);
        });
  }

  /** A pacs.028: the stored state of the transfer it names, 404 when unknown. */
  public SimReply status(String document) {
    NpsbIsoXml.Reference ref;
    try {
      ref = NpsbIsoXml.readPacs028(document);
    } catch (IllegalArgumentException e) {
      return new SimReply(400, Map.of("code", "FF01", "message", "not a pacs.028"));
    }
    Decision d = control.decide(PARTNER, "status", Map.of("txnRef", nz(ref.endToEndId())), SimMode.SUCCESS);
    return MockCalls.answer(
        d,
        PARTNER,
        () -> Optional.ofNullable(current(ref.endToEndId())).map(this::status)
            .orElse(new SimReply(404, Map.of("code", "NOT_FOUND", "message", "no such transfer"))),
        () -> new SimReply(503, Map.of("code", "5030", "message", "maintenance")),
        () -> new SimReply(500, Map.of("code", "5000", "message", "error")),
        code -> new SimReply(404, Map.of("code", "NOT_FOUND", "message", "no such transfer")));
  }

  /** A camt.056: CNCL once for a settled transfer, RJCR otherwise (not settled, unknown or returned). */
  public SimReply returnTransfer(String document) {
    NpsbIsoXml.Reference ref;
    try {
      ref = NpsbIsoXml.readCamt056(document);
    } catch (IllegalArgumentException e) {
      return new SimReply(400, Map.of("code", "FF01", "message", "not a camt.056"));
    }
    Decision d = control.decide(PARTNER, "return", Map.of("txnRef", nz(ref.endToEndId())), SimMode.SUCCESS);
    return MockCalls.answer(
        d,
        PARTNER,
        () -> {
          Transfer t = current(ref.endToEndId());
          String conf = "RJCR";
          if (t != null && "ACSC".equals(t.status()) && !t.returned()) {
            synchronized (transfers) {
              Transfer again = transfers.get(ref.endToEndId());
              if (!again.returned()) {
                transfers.put(ref.endToEndId(), new Transfer(again.in(), again.at(), again.status(), again.reason(),
                    null, null, true, again.clearingRef()));
                conf = "CNCL";
              }
            }
          }
          return resolution(ref, t, conf);
        },
        () -> new SimReply(503, Map.of("code", "5030", "message", "maintenance")),
        () -> new SimReply(500, Map.of("code", "5000", "message", "error")),
        code -> resolution(ref, current(ref.endToEndId()), "RJCR"));
  }

  /** Pushes a pacs.008 to the middleware (once, twice, late or not at all) and answers what it said. */
  public InboundResult inbound(InboundPush p) {
    String e2e = p.endToEndId() == null || p.endToEndId().isBlank()
        ? "NPSB" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT).substring(0, 28)
        : p.endToEndId();
    Decision d = control.decide(PARTNER, "inbound", Map.of("txnRef", e2e), SimMode.SUCCESS);
    Optional<SimReply> closed = MockCalls.admit(d, PARTNER, () -> new SimReply(503, Map.of()));
    List<NpsbInboundClient.Answer> answers = new ArrayList<>();
    if (closed.isPresent() || d.callback() == CallbackMode.NONE) {
      return new InboundResult(e2e, answers);
    }
    OffsetDateTime at = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC).withNano(0)
        .minusMinutes(p.ageMinutes() == null ? 0 : p.ageMinutes());
    String xml = signer.sign(NpsbIsoXml.pacs008("M" + e2e, at, e2e, "T" + e2e,
        p.amountMinor() == null ? 100_000 : p.amountMinor(), "NPSB Sender", "1234567890123",
        p.debtorAgent() == null ? "FAKEBDD1" : p.debtorAgent(), "NPSB Beneficiary", p.creditorAccount(),
        p.creditorAgent() == null ? "BITSBDDH" : p.creditorAgent()));
    if (d.callback() == CallbackMode.LATE) {
      MockCalls.pause(d.callbackDelayMs());
    }
    int copies = d.callback() == CallbackMode.DUPLICATE ? 2 : 1;
    for (int i = 0; i < copies; i++) {
      answers.add(client.post(inboundUrl, xml));
    }
    log.atInfo()
        .addKeyValue("event", "sim.npsb.inbound")
        .addKeyValue("partnerId", PARTNER)
        .log("pushed inbound pacs.008 {} ({} copies)", e2e, copies);
    return new InboundResult(e2e, answers);
  }

  @Override
  public void reset(String party) {
    if (PARTNER.equals(party)) {
      transfers.clear();
    }
  }

  // ---- triggers --------------------------------------------------------------------------------

  private Transfer book(NpsbIsoXml.Transfer in) {
    Instant now = clock.instant();
    String clr = "NPSB-CLR-" + refs.incrementAndGet();
    Optional<NpsbParticipant> to = PARTICIPANTS.stream().filter(p -> p.id().equals(in.creditorAgent())).findFirst();
    if (to.isEmpty() || !to.get().active()) {
      return new Transfer(in, now, "RJCT", "RC01", null, null, false, null);
    }
    return switch (suffix(in.creditorAccount())) {
      case "991" -> new Transfer(in, now, "RJCT", "AC01", null, null, false, null);
      case "992" -> new Transfer(in, now, "RJCT", "AM04", null, null, false, null);
      case "993" -> new Transfer(in, now, "PDNG", null, "ACSC", null, false, clr);
      case "995" -> new Transfer(in, now, "PDNG", null, "RJCT", "AC01", false, null);
      default -> new Transfer(in, now, "ACSC", null, null, null, false, clr);
    };
  }

  private Transfer current(String e2e) {
    if (e2e == null) {
      return null;
    }
    return transfers.computeIfPresent(e2e, (k, t) -> t.settled(clock.instant(), settleDelay));
  }

  private SimReply status(Transfer t) {
    String msgId = "NPSBST" + refs.incrementAndGet();
    String doc = signer.sign(NpsbIsoXml.pacs002(msgId, OffsetDateTime.now(clock), t.in().msgId(), t.in().endToEndId(),
        t.in().txId(), t.status(), t.reason(), "ACSC".equals(t.status()) ? t.clearingRef() : null));
    return new SimReply(200, body(msgId, doc));
  }

  private SimReply resolution(NpsbIsoXml.Reference ref, Transfer t, String conf) {
    String id = "NPSBRS" + refs.incrementAndGet();
    String doc = signer.sign(NpsbIsoXml.camt029(id, OffsetDateTime.now(clock), ref.msgId(), conf, ref.endToEndId(),
        ref.txId(), OWN_BIC, t == null ? OWN_BIC : t.in().debtorAgent()));
    return new SimReply(200, body(id, doc));
  }

  private static Map<String, Object> body(String msgId, String document) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("msgId", msgId);
    m.put("document", document);
    return m;
  }

  private static String suffix(String account) {
    return account == null || account.length() < 3 ? "" : account.substring(account.length() - 3);
  }

  private static String nz(String s) {
    return s == null ? "" : s;
  }
}
