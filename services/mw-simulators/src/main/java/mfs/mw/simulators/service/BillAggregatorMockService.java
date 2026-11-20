package mfs.mw.simulators.service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import mfs.mw.simulators.dto.AggregatorProfile;
import mfs.mw.simulators.dto.SimMode;
import mfs.mw.simulators.dto.SimReply;
import mfs.mw.simulators.exception.ErrorCodes;
import mfs.mw.simulators.exception.MockAbortException;
import mfs.mw.simulators.exception.MwException;
import mfs.mw.simulators.service.AggregatorBillLedger.Bill;
import mfs.mw.simulators.service.AggregatorBillLedger.Payment;
import mfs.mw.simulators.service.mockcontrol.Decision;
import mfs.mw.simulators.service.mockcontrol.MockCalls;
import mfs.mw.simulators.service.mockcontrol.MockControl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * The four bill aggregator mocks (ticket 114, FRS UC-10, EXT-10, MCK-02/03/06/08): {@code sslcommerz},
 * {@code ekpay}, {@code agg3} (SOAP), {@code agg4}, each a mock party named after the profile with
 * operations {@code fetch}, {@code pay}, {@code status}, {@code reverse} and keys {@code biller}, {@code
 * billNumber}, {@code txnRef}. Bills are seeded and stateful ({@link AggregatorBillLedger}). Replies are
 * semantic maps (keys: the {@link AggregatorProfile} field names); the controller renders them in the
 * profile's dialect (REST JSON, an envelope, or SOAP).
 *
 * <p>Outcomes (the trigger table is in this module's CLAUDE.md): unknown bill NOT_FOUND, unknown biller
 * UNKNOWN_BILLER, a PAID bill PAID (fetch and pay), an amount other than the bill's AMOUNT_MISMATCH,
 * bill number ending {@code 999} REFUSED, a reverse of an unpaid payment NOT_PAID; amounts as {@link
 * MagicAmounts}. A repeated reference answers the stored payment; a second reverse the first reversal.
 * Callback-only (setting {@code callbackOnly}) answers a payment 202 pending, the outcome by callback.
 */
@Service
public class BillAggregatorMockService {

  public static final String AMBIGUOUS = "9876";

  private static final Logger log = LoggerFactory.getLogger(BillAggregatorMockService.class);
  private static final Map<String, String> MESSAGES = Map.of(
      "NOT_FOUND", "Bill not found", "PAID", "Bill already paid", "REFUSED", "Payment refused",
      "UNKNOWN_BILLER", "Unknown biller", "NOT_PAID", "Payment not reversible", "AMOUNT_MISMATCH", "Amount mismatch");

  private final MockControl control;
  private final AggregatorBillLedger ledger;
  private final CallbackService callbacks;
  private final JsonMapper json;
  private final Set<String> tokens = ConcurrentHashMap.newKeySet();

  public BillAggregatorMockService(
      MockControl control, AggregatorBillLedger ledger, CallbackService callbacks, JsonMapper json) {
    this.control = control;
    this.ledger = ledger;
    this.callbacks = callbacks;
    this.json = json;
  }

  public static AggregatorProfile profile(String id) {
    return AggregatorProfile.ALL.stream().filter(p -> p.id().equals(id)).findFirst()
        .orElseThrow(() -> new MwException(ErrorCodes.VAL_NOT_FOUND, "No such aggregator").withStatus(HttpStatus.NOT_FOUND));
  }

  /** A client-credentials token (ekpay): any Basic client; the token is then accepted as Bearer. */
  public Map<String, Object> token() {
    String token = "agg-" + UUID.randomUUID();
    tokens.add(token);
    return Map.of("access_token", token, "token_type", "Bearer", "expires_in", 3600);
  }

  public boolean tokenKnown(String bearer) {
    return bearer != null && tokens.contains(bearer);
  }

  public SimReply fetch(AggregatorProfile p, String billerCode, String billNumber) {
    if (blank(billerCode) || blank(billNumber)) {
      return invalid();
    }
    Decision d = control.decide(p.id(), "fetch", Map.of("biller", billerCode, "billNumber", billNumber), SimMode.SUCCESS);
    return MockCalls.answer(d, p.id(), () -> {
      Optional<Bill> bill = ledger.bill(p.id(), billerCode, billNumber);
      if (bill.isEmpty()) {
        return refusal(p, null, ledger.knowsBiller(p.id(), billerCode) ? "NOT_FOUND" : "UNKNOWN_BILLER");
      }
      Bill b = bill.get();
      return new SimReply(200, body("status", b.paid() ? p.word("paid") : p.word("unpaid"), "billerCode", b.billerCode(),
          "billNumber", b.billNumber(), "amount", b.amount().toPlainString(), "dueDate", b.dueDate(),
          "period", b.billPeriod()));
    }, BillAggregatorMockService::unavailable, BillAggregatorMockService::internalError, code -> refusalCode(p, null, code));
  }

  public SimReply pay(AggregatorProfile p, String reference, String billerCode, String billNumber, String amount) {
    if (blank(reference) || reference.length() > 64 || blank(billerCode) || blank(billNumber) || amount(amount) == null) {
      return invalid();
    }
    SimMode magic = billNumber.endsWith("999") ? SimMode.BUSINESS_FAILURE : MagicAmounts.mode(amount);
    Decision d = control.decide(p.id(), "pay", Map.of("biller", billerCode, "billNumber", billNumber, "txnRef", reference), magic);
    Optional<SimReply> closed = MockCalls.admit(d, p.id(), BillAggregatorMockService::unavailable);
    if (closed.isPresent()) {
      return closed.get();
    }
    Optional<Payment> existing = ledger.payment(p.id(), reference);
    if (existing.isPresent()) {
      return paymentReply(p, existing.get());
    }
    // M9 review: the bill check runs inside the ledger lock, so one bill is never paid twice at once
    java.util.function.Supplier<String> refusal = () -> switch (d.mode()) {
      case BUSINESS_FAILURE -> d.code() != null ? "CODE:" + d.code() : "REFUSED";
      case UNKNOWN_CODE -> "CODE:" + d.codeOr(AMBIGUOUS);
      default -> check(p, billerCode, billNumber, amount);
    };
    boolean pending = control.settings(p.id()).callbackOnly();
    Payment payment = ledger.pay(p.id(), reference, billerCode, billNumber, amount, refusal, pending);
    log.atInfo().addKeyValue("event", "sim.agg.payment.applied").addKeyValue("partnerId", p.id())
        .log("payment {} status {} ({})", reference, payment.status(), d.source());
    if (d.mode() == SimMode.TIMEOUT) {
      MockCalls.pause(d.holdMs());
    } else {
      callbacks.afterBankTransfer(p.id(), d, json.writeValueAsString(wire(p, settled(p, payment))),
          json.writeValueAsString(wire(p, body("reference", reference,
              "status", p.word("pending"), "paymentRef", payment.paymentRef()))));
    }
    return switch (d.mode()) {
      case SERVER_ERROR_AFTER_SEND -> new SimReply(d.statusOr(500), internalError().body());
      case MALFORMED -> new SimReply(200, MockCalls.CUT_SHORT);
      case CONNECTION_RESET -> throw new MockAbortException(p.id());
      default -> paymentReply(p, payment);
    };
  }

  public SimReply status(AggregatorProfile p, String reference) {
    Decision d = control.decide(p.id(), "status", Map.of("txnRef", reference), SimMode.SUCCESS);
    return MockCalls.answer(d, p.id(),
        () -> ledger.payment(p.id(), reference).map(pay -> new SimReply(200, settled(p, pay)))
            .orElseGet(() -> new SimReply(404, body("reference", reference, "status", "NOT_FOUND", "code", "4040",
                "message", "No such payment"))),
        BillAggregatorMockService::unavailable, BillAggregatorMockService::internalError, code -> refusalCode(p, reference, code));
  }

  public SimReply reverse(AggregatorProfile p, String reference) {
    Decision d = control.decide(p.id(), "reverse", Map.of("txnRef", reference), SimMode.SUCCESS);
    return MockCalls.answer(d, p.id(), () -> {
      Optional<Payment> before = ledger.payment(p.id(), reference);
      if (before.isEmpty()) {
        return new SimReply(404, body("reference", reference, "status", "NOT_FOUND", "code", p.code("NOT_FOUND"),
            "message", "No such payment"));
      }
      if (!before.get().succeeded() && !"REVERSED".equals(before.get().status())) {
        return refusal(p, reference, "NOT_PAID");
      }
      Payment r = ledger.reverse(p.id(), reference);
      return new SimReply(200, body("reference", reference, "status", p.word("reversed"), "reversalRef", r.reversalRef()));
    }, BillAggregatorMockService::unavailable, BillAggregatorMockService::internalError, code -> refusalCode(p, reference, code));
  }

  /** The health probe a partner record with {@code healthCheck: true} sends (samplebiller, ticket 136). */
  public SimReply health(AggregatorProfile p) {
    Decision d = control.decide(p.id(), "health", Map.of(), SimMode.SUCCESS);
    return MockCalls.answer(d, p.id(), () -> new SimReply(200, body("status", "UP")),
        BillAggregatorMockService::unavailable, BillAggregatorMockService::internalError, code -> refusalCode(p, null, code));
  }

  /** The bill's state for tests ({@code GET /sim/agg/{profile}/bills/{biller}/{billNumber}}). */
  public Map<String, Object> bill(String profile, String billerCode, String billNumber) {
    Bill b = ledger.bill(profile(profile).id(), billerCode, billNumber)
        .orElseThrow(() -> new MwException(ErrorCodes.VAL_NOT_FOUND, "No such bill").withStatus(HttpStatus.NOT_FOUND));
    return body("profile", profile, "billerCode", b.billerCode(), "billNumber", b.billNumber(),
        "amount", b.amount().toPlainString(), "status", b.status());
  }

  /** Renames a semantic body into the profile's REST field names (no envelope). */
  public static Map<String, Object> wire(AggregatorProfile p, Map<String, Object> semantic) {
    Map<String, Object> out = new LinkedHashMap<>();
    semantic.forEach((k, v) -> out.put(p.field(k), v));
    return out;
  }

  private String check(AggregatorProfile p, String billerCode, String billNumber, String amount) {
    Optional<Bill> bill = ledger.bill(p.id(), billerCode, billNumber);
    if (bill.isEmpty()) {
      return ledger.knowsBiller(p.id(), billerCode) ? "NOT_FOUND" : "UNKNOWN_BILLER";
    }
    if (bill.get().paid()) {
      return "PAID";
    }
    return bill.get().amount().compareTo(amount(amount)) != 0 ? "AMOUNT_MISMATCH" : null;
  }

  private static Map<String, Object> settled(AggregatorProfile p, Payment pay) {
    String code = pay.code() == null ? null : pay.code().startsWith("CODE:") ? pay.code().substring(5) : p.code(pay.code());
    String status = switch (pay.status()) {
      case "SUCCESS" -> p.word("paid");
      case "REVERSED" -> p.word("reversed");
      default -> p.word("failed");
    };
    return body("reference", pay.reference(), "status", status, "paymentRef", pay.paymentRef(), "code", code,
        "message", code == null ? null : MESSAGES.getOrDefault(pay.code(), "Refused"));
  }

  private static SimReply paymentReply(AggregatorProfile p, Payment pay) {
    if (pay.succeeded() || "REVERSED".equals(pay.status())) {
      return pay.pending()
          ? new SimReply(202, body("reference", pay.reference(), "status", p.word("pending"), "paymentRef", pay.paymentRef()))
          : new SimReply(200, settled(p, pay));
    }
    return new SimReply(422, settled(p, pay));
  }

  private static SimReply refusal(AggregatorProfile p, String reference, String name) {
    return new SimReply(422, body("reference", reference, "status", p.word("failed"), "code", p.code(name),
        "message", MESSAGES.getOrDefault(name, "Refused")));
  }

  private static SimReply refusalCode(AggregatorProfile p, String reference, String code) {
    return new SimReply(422, body("reference", reference, "status", p.word("failed"),
        "code", code == null ? p.code("REFUSED") : code, "message", "Refused"));
  }

  static SimReply unavailable() {
    return new SimReply(503, body("status", "UNAVAILABLE", "code", "5030", "message", "Service unavailable"));
  }

  static SimReply internalError() {
    return new SimReply(500, body("status", "ERROR", "code", "5000", "message", "Internal error"));
  }

  private static SimReply invalid() {
    return new SimReply(400, body("status", "FAILED", "code", "1000", "message", "invalid request"));
  }

  private static BigDecimal amount(String amount) {
    try {
      BigDecimal v = amount == null ? null : new BigDecimal(amount);
      return v != null && v.signum() > 0 && v.scale() <= 2 ? v : null;
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }

  /** A body map of name/value pairs; null names or values are left out. */
  static Map<String, Object> body(Object... kv) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      if (kv[i] != null && kv[i + 1] != null) {
        m.put((String) kv[i], kv[i + 1]);
      }
    }
    return m;
  }
}
