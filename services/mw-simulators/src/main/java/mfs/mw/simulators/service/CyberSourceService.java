package mfs.mw.simulators.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import mfs.mw.simulators.dto.CyberSourceRequests;
import mfs.mw.simulators.dto.SimMode;
import mfs.mw.simulators.dto.SimReply;
import mfs.mw.simulators.exception.MockAbortException;
import mfs.mw.simulators.service.mockcontrol.Decision;
import mfs.mw.simulators.service.mockcontrol.MockCalls;
import mfs.mw.simulators.service.mockcontrol.MockControl;
import mfs.mw.simulators.service.mockcontrol.PartyState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * CyberSource-style card acquirer (ticket 118, FRS UC-13, EXT-14, FL-07, MCK-06; mock party {@code
 * cybersource}, operations {@code session}, {@code authenticate}, {@code capture}, {@code status},
 * {@code refund}, keys {@code txnRef} and {@code session}). The card is typed on the acquirer's hosted page
 * only ({@link #hostedPage}, {@link #submitCard}): the simulator keeps nothing of it but the test card's
 * outcome behind a transient token, and the middleware sees only the session id and that token.
 *
 * <p>Test cards (the magic values; mock control overrides them): {@code 4111111111111111} success, {@code
 * 4000000000000002} declined ({@code 4001}), {@code 4000000000003063} 3-D Secure failed ({@code 4002}, the
 * webhook says FAILED), {@code 4000000000000119} capture timeout (captured, the answer held {@code
 * sim.timeout-delay}: only the status inquiry learns it); any other number is declined. After the hosted
 * page the 3-D Secure result goes as a signed webhook to the party's {@code callbackUrl} (once, duplicate,
 * late, none: mock control), body {@code {clientReference, sessionId, transientToken, status AUTHENTICATED
 * | FAILED, code}}. A capture is applied once per {@code clientReference}, a refund once per payment.
 */
@Service
public class CyberSourceService implements PartyState {

  public static final String PARTNER = "cybersource";
  public static final String CARD_SUCCESS = "4111111111111111";
  public static final String CARD_DECLINE = "4000000000000002";
  public static final String CARD_3DS_FAIL = "4000000000003063";
  public static final String CARD_TIMEOUT = "4000000000000119";

  private static final Logger log = LoggerFactory.getLogger(CyberSourceService.class);

  /** The outcome a test card stands for. */
  enum CardOutcome { SUCCESS, DECLINED, THREE_DS_FAILED, TIMEOUT }

  record Session(String sessionId, String clientReference, String amount, String currency) {}

  record Token(String token, String sessionId, CardOutcome outcome) {}

  record Payment(String clientReference, String paymentId, String status, String code, String amount,
      String refundId) {
    Payment refunded(String id) {
      return new Payment(clientReference, paymentId, status, code, amount, id);
    }
  }

  private final MockControl control;
  private final CallbackService callbacks;
  private final JsonMapper json;
  private final String hostedPageBase;
  private final Map<String, Session> sessions = new ConcurrentHashMap<>();
  private final Map<String, Token> tokens = new ConcurrentHashMap<>();
  private final Map<String, Payment> payments = new ConcurrentHashMap<>();
  private final AtomicLong refs = new AtomicLong();

  public CyberSourceService(
      MockControl control,
      CallbackService callbacks,
      JsonMapper json,
      @Value("${sim.cybersource.hosted-page-base:https://localhost/cybersource/hp}") String hostedPageBase) {
    this.control = control;
    this.callbacks = callbacks;
    this.json = json;
    this.hostedPageBase = hostedPageBase;
  }

  /** CREATE_SESSION: a hosted page session for the amount. */
  public SimReply createSession(CyberSourceRequests.Session req) {
    Decision d = control.decide(PARTNER, "session", Map.of("txnRef", req.clientReference()), SimMode.SUCCESS);
    return MockCalls.answer(
        d,
        PARTNER,
        () -> {
          Session s = sessions.values().stream()
              .filter(x -> x.clientReference().equals(req.clientReference()))
              .findFirst()
              .orElseGet(() -> {
                Session created = new Session("sess-" + UUID.randomUUID(), req.clientReference(), req.amount(),
                    req.currency() == null ? "BDT" : req.currency());
                sessions.put(created.sessionId(), created);
                return created;
              });
          return new SimReply(201, Map.of(
              "status", "CREATED", "sessionId", s.sessionId(), "hostedPageUrl", hostedPageBase + "/" + s.sessionId()));
        },
        () -> new SimReply(503, refusal("UNAVAILABLE", "5030", "maintenance")),
        () -> new SimReply(500, refusal("ERROR", "5000", "internal error")),
        code -> new SimReply(422, refusal("FAILED", code == null ? "4005" : code, "session refused")));
  }

  /** The hosted page's card form, or empty for an unknown session. */
  public Optional<String> hostedPage(String sessionId) {
    Session s = sessions.get(sessionId);
    if (s == null) {
      return Optional.empty();
    }
    return Optional.of("<!doctype html><html><head><meta charset=\"utf-8\"><title>Card payment (test)</title></head>"
        + "<body><h1>Pay " + s.amount() + " " + s.currency() + "</h1>"
        + "<p>Test acquirer page. Test cards: " + CARD_SUCCESS + " success, " + CARD_DECLINE + " declined, "
        + CARD_3DS_FAIL + " 3-D Secure failed, " + CARD_TIMEOUT + " capture timeout.</p>"
        + "<form method=\"post\" action=\"" + sessionId + "\"><label>Card number <input name=\"cardNumber\" "
        + "autocomplete=\"off\" inputmode=\"numeric\"></label> <button type=\"submit\">Pay</button></form></body></html>");
  }

  /**
   * The customer submits a card on the hosted page: the card becomes a transient token (its number is
   * dropped here), the 3-D Secure result goes by webhook. Answers {@code {sessionId, transientToken, status,
   * code}}; empty for an unknown session.
   */
  public Optional<Map<String, Object>> submitCard(String sessionId, String cardNumber) {
    Session s = sessions.get(sessionId);
    if (s == null) {
      return Optional.empty();
    }
    CardOutcome outcome = outcome(cardNumber);
    Token t = new Token("tok-" + UUID.randomUUID(), sessionId, outcome);
    tokens.put(t.token(), t);
    Decision d = control.decide(PARTNER, "authenticate", Map.of("txnRef", s.clientReference(), "session", sessionId),
        outcome == CardOutcome.THREE_DS_FAILED ? SimMode.BUSINESS_FAILURE : SimMode.SUCCESS);
    boolean authenticated = d.mode() != SimMode.BUSINESS_FAILURE;
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("clientReference", s.clientReference());
    body.put("sessionId", sessionId);
    body.put("transientToken", t.token());
    body.put("status", authenticated ? "AUTHENTICATED" : "FAILED");
    body.put("code", authenticated ? "100" : d.codeOr("4002"));
    log.atInfo()
        .addKeyValue("event", "sim.cybersource.card.authenticated")
        .addKeyValue("partnerId", PARTNER)
        .log("session {} 3ds {} ({})", sessionId, body.get("status"), d.source());
    callbacks.afterCardAuthentication(PARTNER, d, json.writeValueAsString(body));
    return Optional.of(body);
  }

  /** AUTHORISE_CAPTURE of an authenticated session's token, once per client reference. */
  public SimReply capture(CyberSourceRequests.Payment req) {
    Token t = tokens.get(req.transientToken());
    SimMode magic = t == null || !t.sessionId().equals(req.sessionId())
        ? SimMode.BUSINESS_FAILURE
        : switch (t.outcome()) {
          case SUCCESS -> SimMode.SUCCESS;
          case TIMEOUT -> SimMode.TIMEOUT;
          default -> SimMode.BUSINESS_FAILURE;
        };
    Decision d = control.decide(
        PARTNER, "capture", Map.of("txnRef", req.clientReference(), "session", req.sessionId()), magic);
    Optional<SimReply> closed = MockCalls.admit(d, PARTNER, () -> new SimReply(503, refusal("UNAVAILABLE", "5030", "maintenance")));
    if (closed.isPresent()) {
      return closed.get();
    }
    Payment existing = payments.get(req.clientReference());
    if (existing != null) {
      return answer(existing);
    }
    Payment p;
    if (d.mode() == SimMode.BUSINESS_FAILURE) {
      String code = d.codeOr(t != null && t.outcome() == CardOutcome.THREE_DS_FAILED ? "4002" : "4001");
      p = new Payment(req.clientReference(), null, "DECLINED", code, req.amount(), null);
    } else {
      p = new Payment(req.clientReference(), "pay-" + refs.incrementAndGet(), "CAPTURED", "100", req.amount(), null);
    }
    Payment stored = Optional.ofNullable(payments.putIfAbsent(req.clientReference(), p)).orElse(p);
    log.atInfo()
        .addKeyValue("event", "sim.cybersource.capture")
        .addKeyValue("partnerId", PARTNER)
        .log("capture {} {} ({})", stored.clientReference(), stored.status(), d.source());
    return switch (d.mode()) {
      case TIMEOUT -> {
        MockCalls.pause(d.holdMs());
        yield answer(stored);
      }
      case SERVER_ERROR_AFTER_SEND -> new SimReply(d.statusOr(500), refusal("ERROR", "5000", "internal error"));
      case MALFORMED -> new SimReply(201, MockCalls.CUT_SHORT);
      case CONNECTION_RESET -> throw new MockAbortException(PARTNER);
      default -> answer(stored);
    };
  }

  /** The real outcome of a capture, whatever the caller saw. */
  public SimReply status(String clientReference) {
    Decision d = control.decide(PARTNER, "status", Map.of("txnRef", clientReference), SimMode.SUCCESS);
    return MockCalls.answer(
        d,
        PARTNER,
        () -> Optional.ofNullable(payments.get(clientReference))
            .map(p -> new SimReply(200, body(p)))
            .orElseGet(() -> new SimReply(404, refusal("NOT_FOUND", "4040", "no such payment"))),
        () -> new SimReply(503, refusal("UNAVAILABLE", "5030", "maintenance")),
        () -> new SimReply(500, refusal("ERROR", "5000", "internal error")),
        code -> new SimReply(422, refusal("FAILED", code, "refused")));
  }

  /** REFUND of a captured payment, once (a repeat answers the first refund). */
  public SimReply refund(String clientReference, CyberSourceRequests.Refund req) {
    Decision d = control.decide(PARTNER, "refund", Map.of("txnRef", clientReference), SimMode.SUCCESS);
    return MockCalls.answer(
        d,
        PARTNER,
        () -> {
          Payment p = payments.get(clientReference);
          if (p == null) {
            return new SimReply(404, refusal("NOT_FOUND", "4040", "no such payment"));
          }
          if (!"CAPTURED".equals(p.status())) {
            return new SimReply(422, refusal("FAILED", "4004", "payment not captured"));
          }
          Payment refunded = payments.compute(clientReference, (k, v) -> v.refundId() != null ? v : v.refunded("rf-" + refs.incrementAndGet()));
          return new SimReply(201, Map.of("status", "REFUNDED", "refundId", refunded.refundId(), "code", "100"));
        },
        () -> new SimReply(503, refusal("UNAVAILABLE", "5030", "maintenance")),
        () -> new SimReply(500, refusal("ERROR", "5000", "internal error")),
        code -> new SimReply(422, refusal("FAILED", code == null ? "4004" : code, "refund refused")));
  }

  @Override
  public void reset(String party) {
    if (PARTNER.equals(party)) {
      sessions.clear();
      tokens.clear();
      payments.clear();
    }
  }

  static CardOutcome outcome(String cardNumber) {
    String digits = cardNumber == null ? "" : cardNumber.replaceAll("[\\s-]", "");
    return switch (digits) {
      case CARD_SUCCESS -> CardOutcome.SUCCESS;
      case CARD_3DS_FAIL -> CardOutcome.THREE_DS_FAILED;
      case CARD_TIMEOUT -> CardOutcome.TIMEOUT;
      default -> CardOutcome.DECLINED;
    };
  }

  private static SimReply answer(Payment p) {
    return "CAPTURED".equals(p.status()) ? new SimReply(201, body(p)) : new SimReply(422, body(p));
  }

  private static Map<String, Object> body(Payment p) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("clientReference", p.clientReference());
    m.put("status", p.status());
    m.put("code", p.code());
    if (p.paymentId() != null) {
      m.put("paymentId", p.paymentId());
    }
    if (p.refundId() != null) {
      m.put("refundId", p.refundId());
    }
    return m;
  }

  private static Map<String, Object> refusal(String status, String code, String message) {
    return Map.of("status", status, "code", code, "message", message);
  }
}
