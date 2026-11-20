package mfs.mw.simulators.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import mfs.mw.simulators.dto.SimMode;
import mfs.mw.simulators.dto.SimReply;
import mfs.mw.simulators.service.mockcontrol.Decision;
import mfs.mw.simulators.service.mockcontrol.MockCalls;
import mfs.mw.simulators.service.mockcontrol.MockControl;
import mfs.mw.simulators.service.mockcontrol.PartyState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Grameenphone, Robi, Teletalk and Banglalink OCS profiles (ticket 112a, FRS EXT-08): each has its
 * own JSON and error vocabulary. Postpaid bill fetch reads the seeded account. A recharge that
 * succeeded is reversed once; a later reverse returns that first result (MCK-02).
 */
@Service
public class OperatorOcsService implements PartyState {

  /** The token {@code POST /ocs-teletalk/oauth/token} returns. Dev only, not a secret. */
  public static final String TELETALK_TOKEN = "dev-teletalk-token";

  private static final Logger log = LoggerFactory.getLogger(OperatorOcsService.class);

  private final MockControl control;
  private final Map<String, Stored> ledger = new ConcurrentHashMap<>();
  private final Map<String, Map<String, Object>> reversals = new ConcurrentHashMap<>();
  private final AtomicLong refs = new AtomicLong();

  public OperatorOcsService(MockControl control) {
    this.control = control;
  }

  public SimReply recharge(OperatorOcsProfile profile, Map<String, Object> body) {
    String txn = text(body.get(profile.txnField()));
    String msisdn = text(body.get(profile.msisdnField()));
    String amount = text(body.get(profile.amountField()));
    if (txn == null || msisdn == null || amount == null) {
      return missing();
    }
    SimMode magic = msisdn.endsWith("999") ? SimMode.BUSINESS_FAILURE : SimMode.SUCCESS;
    Decision d = control.decide(profile.party(), "topup", Map.of("msisdn", msisdn, "txnRef", txn), magic);
    return MockCalls.answer(
        d,
        profile.party(),
        () -> apply(profile, txn, false),
        () -> unavailable(profile),
        () -> new SimReply(500, profile.failure("5000", "internal error")),
        code -> refused(profile, code == null ? profile.barredCode() : code, profile.barredMessage()));
  }

  public SimReply bill(OperatorOcsProfile profile, Map<String, Object> body) {
    String msisdn = text(body.get(profile.msisdnField()));
    if (msisdn == null) {
      return missing();
    }
    if (msisdn.endsWith("999")) {
      return refused(profile, profile.barredCode(), profile.barredMessage());
    }
    if (!msisdn.equals(profile.seedMsisdn())) {
      return refused(profile, profile.unknownBillCode(), "No postpaid account");
    }
    return new SimReply(200, profile.bill(profile.seedOutstanding(), profile.seedMinimum(), profile.seedBillRef()));
  }

  public SimReply payBill(OperatorOcsProfile profile, Map<String, Object> body) {
    String txn = text(body.get(profile.txnField()));
    String msisdn = text(body.get(profile.msisdnField()));
    String amount = text(body.get(profile.amountField()));
    if (txn == null || msisdn == null || amount == null) {
      return missing();
    }
    if (msisdn.endsWith("999")) {
      return refused(profile, profile.barredCode(), profile.barredMessage());
    }
    if (!msisdn.equals(profile.seedMsisdn())) {
      return refused(profile, profile.unknownBillCode(), "No postpaid account");
    }
    Decision d = control.decide(profile.party(), "postpaid", Map.of("msisdn", msisdn, "txnRef", txn), SimMode.SUCCESS);
    return MockCalls.answer(
        d,
        profile.party(),
        () -> apply(profile, txn, true),
        () -> unavailable(profile),
        () -> new SimReply(500, profile.failure("5000", "internal error")),
        code -> refused(profile, code == null ? profile.barredCode() : code, profile.barredMessage()));
  }

  /** The stored outcome, HTTP 200. Unknown reference is 404. */
  public SimReply status(OperatorOcsProfile profile, String txn) {
    Decision d = control.decide(profile.party(), "status", Map.of("txnRef", txn), SimMode.SUCCESS);
    return MockCalls.answer(
        d,
        profile.party(),
        () -> {
          Stored stored = ledger.get(key(profile, txn));
          if (stored == null) {
            return new SimReply(404, profile.notFound());
          }
          return new SimReply(200, stored.statusBody(profile));
        },
        () -> unavailable(profile),
        () -> new SimReply(500, profile.failure("5000", "internal error")),
        code -> refused(profile, code == null ? profile.barredCode() : code, profile.barredMessage()));
  }

  /**
   * Reverses a successful recharge once. A second reverse returns the first body, including the
   * same operator reference.
   */
  public SimReply reverse(OperatorOcsProfile profile, String txn, Map<String, Object> body) {
    Decision d = control.decide(profile.party(), "reverse", Map.of("txnRef", txn), SimMode.SUCCESS);
    return MockCalls.answer(
        d,
        profile.party(),
        () -> applyReverse(profile, txn),
        () -> unavailable(profile),
        () -> new SimReply(500, profile.failure("5000", "internal error")),
        code -> refused(profile, code == null ? profile.notReversibleCode() : code, "Cannot reverse"));
  }

  public SimReply token() {
    return new SimReply(
        200, Map.of("access_token", TELETALK_TOKEN, "token_type", "bearer", "expires_in", 3600));
  }

  @Override
  public void reset(String party) {
    OperatorOcsProfile profile = OperatorOcsProfile.byParty(party);
    if (profile == null) {
      return;
    }
    String prefix = profile.party() + "/";
    ledger.keySet().removeIf(k -> k.startsWith(prefix));
    reversals.keySet().removeIf(k -> k.startsWith(prefix));
  }

  private SimReply apply(OperatorOcsProfile profile, String txn, boolean postpaid) {
    Stored stored = ledger.computeIfAbsent(key(profile, txn), k -> {
      String ref = profile.name() + "-" + refs.incrementAndGet();
      log.atInfo()
          .addKeyValue("event", "sim.ocs.profile.applied")
          .addKeyValue("partnerId", profile.party())
          .log("recharge {} applied ({})", txn, postpaid ? "postpaid" : "prepaid");
      return Stored.success(profile.success(ref));
    });
    return new SimReply(200, stored.body());
  }

  private SimReply applyReverse(OperatorOcsProfile profile, String txn) {
    Stored stored = ledger.get(key(profile, txn));
    if (stored == null) {
      return new SimReply(404, profile.notFound());
    }
    if (!stored.success()) {
      return refused(profile, profile.notReversibleCode(), "Cannot reverse");
    }
    Map<String, Object> body = reversals.computeIfAbsent(key(profile, txn), k -> {
      String ref = profile.name() + "-R" + refs.incrementAndGet();
      log.atInfo()
          .addKeyValue("event", "sim.ocs.profile.reversed")
          .addKeyValue("partnerId", profile.party())
          .log("reverse of {} applied", txn);
      return profile.reversed(ref);
    });
    return new SimReply(200, body);
  }

  private static SimReply refused(OperatorOcsProfile profile, String code, String message) {
    return new SimReply(profile.rejectStatus(), profile.failure(code, message));
  }

  private static SimReply unavailable(OperatorOcsProfile profile) {
    return new SimReply(503, profile.failure("5030", "Service unavailable"));
  }

  private static SimReply missing() {
    return new SimReply(400, Map.of("message", "transaction, msisdn and amount are required"));
  }

  private static String key(OperatorOcsProfile profile, String txn) {
    return profile.party() + "/" + txn;
  }

  private static String text(Object value) {
    return value instanceof String s && !s.isBlank() ? s : null;
  }

  /** What a recharge really did. Status inquiry rebuilds the operator's body from it. */
  private record Stored(boolean success, Map<String, Object> body) {
    static Stored success(Map<String, Object> body) {
      return new Stored(true, Map.copyOf(body));
    }

    Map<String, Object> statusBody(OperatorOcsProfile profile) {
      if (success) {
        return body;
      }
      Map<String, Object> out = new LinkedHashMap<>(body);
      return Map.copyOf(out);
    }
  }
}
