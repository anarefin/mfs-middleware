package mfs.mw.checkout.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import mfs.mw.checkout.client.AuthClient;
import mfs.mw.checkout.config.CheckoutProperties;
import mfs.mw.checkout.dto.Amount;
import mfs.mw.checkout.dto.ConfirmRequest;
import mfs.mw.checkout.dto.ErrorInfo;
import mfs.mw.checkout.dto.OtpSent;
import mfs.mw.checkout.dto.PageView;
import mfs.mw.checkout.dto.PayResult;
import mfs.mw.checkout.dto.SessionStatus;
import mfs.mw.checkout.dto.StatusMessage;
import mfs.mw.checkout.dto.TxnRequest;
import mfs.mw.checkout.entity.CheckoutSession;
import mfs.mw.checkout.exception.ErrorCodes;
import mfs.mw.checkout.exception.MwException;
import mfs.mw.checkout.mapper.CheckoutMapper;
import mfs.mw.checkout.messaging.EventMetadata;
import mfs.mw.checkout.messaging.EventPublisher;
import mfs.mw.checkout.messaging.MwHeaders;
import mfs.mw.checkout.repository.SessionRepository;
import mfs.mw.checkout.util.MaskingPolicy;
import mfs.mw.checkout.util.Msisdn;
import mfs.mw.checkout.util.SensitiveType;
import mfs.mw.checkout.util.UuidV7;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * The hosted page API (spec 9.3 step 2): the page holds only the pay token from the checkout URL.
 * The customer names the paying wallet, gets an OTP by SMS (sent and checked by mw-auth since
 * ticket 72), then confirms with the OTP and the PIN ciphertext (under the PIN transport key); mw-auth
 * checks the PIN and gives the transaction assertion, and the service submits one MERCHANT_PAY per
 * session carrying it to mw-orchestrator and waits, on the HTTP request's own (virtual) thread, at
 * most the reply budget (spec 7.5). Every state change is a conditional update, so a race cannot pay
 * twice; no call to mw-auth runs inside a database transaction.
 */
@Service
public class CheckoutPayService {

  private static final Logger log = LoggerFactory.getLogger(CheckoutPayService.class);

  private final SessionRepository sessions;
  private final CheckoutProperties props;
  private final CheckoutMapper mapper;
  private final AuthClient auth;
  private final EventPublisher publisher;
  private final ReplyWaiters waiters;
  private final SessionOutcomes outcomes;
  private final ResultLinks links;
  private final Clock clock;

  public CheckoutPayService(
      SessionRepository sessions,
      CheckoutProperties props,
      CheckoutMapper mapper,
      AuthClient auth,
      EventPublisher publisher,
      ReplyWaiters waiters,
      SessionOutcomes outcomes,
      ResultLinks links,
      Clock clock) {
    this.sessions = sessions;
    this.props = props;
    this.mapper = mapper;
    this.auth = auth;
    this.publisher = publisher;
    this.waiters = waiters;
    this.outcomes = outcomes;
    this.links = links;
    this.clock = clock;
  }

  public PageView page(String token) {
    CheckoutSession s = byToken(token);
    SessionStatus status = s.statusAt(clock.instant());
    // only a page that can still pay needs the key (a terminal one shows its redirect)
    PageView.PinKey key = status == SessionStatus.OPEN ? auth.pinKey().map(PageView.PinKey::of).orElse(null) : null;
    return new PageView(
        merchantName(s), s.orderId(), s.description(), new Amount(s.amountMinor(), s.currency()), status,
        s.expiresAt(), s.payerMsisdn() == null ? null : MaskingPolicy.mask(SensitiveType.MSISDN, s.payerMsisdn()),
        key, s.txnId() == null ? null : s.txnId().toString(), links.redirectUrl(s));
  }

  /**
   * A new OTP to {@code wallet}, sent by mw-auth (purpose CHECKOUT, bound to this session; ticket 72).
   * The send is counted on the session first (at most {@code otp-max-sends}); mw-auth also limits the
   * wallet's OTPs per window and refuses an unknown or blocked wallet (passed through).
   */
  public OtpSent sendOtp(String token, String wallet) {
    CheckoutSession s = byToken(token);
    Instant now = clock.instant();
    requireOpen(s, now);
    if (s.otpSends() >= props.otpMaxSends()) {
      throw otpLimit();
    }
    String payer = Msisdn.canonical(wallet);
    if (!sessions.otpRequested(s.sessionId(), payer, props.otpMaxSends(), now)) {
      // a concurrent request changed the session in between
      requireOpen(byToken(token), now);
      throw otpLimit();
    }
    int sessionLeft = props.otpMaxSends() - byToken(token).otpSends();
    OtpSent sent = auth.sendOtp(payer, s.sessionId(), s.locale(), CheckoutMapper.taka(s.amountMinor()), merchantName(s));
    log.atInfo()
        .addKeyValue("event", "checkout.otp.sent")
        .addKeyValue("sessionId", s.sessionId().toString())
        .log("checkout OTP requested");
    return new OtpSent(sent.wallet(), sent.otpExpiresAt(), Math.max(0, Math.min(sessionLeft, sent.sendsLeft())));
  }

  /**
   * mw-auth checks and spends the OTP, then checks the PIN and gives the assertion for exactly the
   * request about to be published (ticket 72, D22); only then OPEN -> PAYING and the MERCHANT_PAY
   * goes out with the assertion; then waits for its outcome within the budget. Every refusal leaves
   * the session OPEN.
   */
  public PayResult confirm(String token, ConfirmRequest req) {
    CheckoutSession s = byToken(token);
    Instant now = clock.instant();
    requireOpen(s, now);
    if (s.payerMsisdn() == null) {
      throw new MwException(ErrorCodes.AUTH_OTP_EXPIRED, "Request an OTP first");
    }
    auth.verifyOtp(s.payerMsisdn(), s.sessionId(), req.otp());
    UUID txnId = UuidV7.next(clock);
    String correlationId = correlationId();
    TxnRequest draft = mapper.toTxnRequest(s, txnId.toString(), correlationId, now);
    String assertion = auth.authorise(mapper.toAuthorisation(draft, req.encryptedPin()));
    if (!sessions.claimForPayment(s.sessionId(), s.payerMsisdn(), txnId, clock.instant())) {
      // paid, cancelled or expired meanwhile, or another wallet asked for an OTP since
      requireOpen(byToken(token), clock.instant());
      throw new MwException(ErrorCodes.AUTH_OTP_EXPIRED, "Request a new OTP");
    }
    TxnRequest txn = draft.withAssertion(assertion);
    CompletableFuture<StatusMessage> answer =
        waiters.register(txn.txnId(), txn.idempotencyKey(), txn.initiator().id());
    try {
      publisher.publish(
          props.requestTopic(), txn.txnId(),
          new EventMetadata(correlationId, txn.txnId(), txn.idempotencyKey(), 1), txn,
          Map.of(MwHeaders.REPLY_TO, props.instanceId()));
    } catch (RuntimeException e) {
      waiters.remove(txn.txnId());
      sessions.releaseClaim(s.sessionId(), txnId, clock.instant());
      throw new MwException(ErrorCodes.SYS_UNAVAILABLE, "Service temporarily unavailable, try again", e)
          .withStatus(HttpStatus.SERVICE_UNAVAILABLE);
    }
    log.atInfo()
        .addKeyValue("event", "txn.request.published")
        .addKeyValue("txnId", txn.txnId())
        .addKeyValue("channel", CheckoutMapper.CHANNEL)
        .addKeyValue("sessionId", s.sessionId().toString())
        .log("checkout payment request published");

    StatusMessage status = await(answer, txn.txnId());
    if (status != null && status.terminal()) {
      outcomes.apply(status);
    }
    CheckoutSession after = byToken(token);
    SessionStatus st = after.statusAt(clock.instant());
    ErrorInfo error =
        st == SessionStatus.FAILED && status != null && status.error() != null ? status.error() : null;
    return new PayResult(st, txn.txnId(), error, links.redirectUrl(after));
  }

  /** The customer gives up on the page: CANCELLED, back to the merchant's cancel URL. */
  public PayResult cancel(String token) {
    CheckoutSession s = byToken(token);
    Instant now = clock.instant();
    requireOpen(s, now);
    if (!sessions.cancel(s.sessionId(), now)) {
      requireOpen(byToken(token), now);
    }
    CheckoutSession after = byToken(token);
    return new PayResult(after.statusAt(clock.instant()), null, null, links.redirectUrl(after));
  }

  /** The signed merchant URL of a terminal session; 409 while it is still open or paying. */
  public String returnUrl(String token) {
    CheckoutSession s = byToken(token);
    String url = links.redirectUrl(s);
    if (url == null) {
      throw new MwException(ErrorCodes.VAL_SESSION_STATE, "The payment is not finished yet")
          .withStatus(HttpStatus.CONFLICT);
    }
    return url;
  }

  private StatusMessage await(CompletableFuture<StatusMessage> answer, String txnId) {
    try {
      return answer.get(props.replyBudget().toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      log.atInfo()
          .addKeyValue("event", "txn.reply.timeout")
          .addKeyValue("txnId", txnId)
          .log("no final status within the reply budget, answering 202");
      return null;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    } catch (ExecutionException e) {
      return null;
    } finally {
      waiters.remove(txnId);
    }
  }

  private CheckoutSession byToken(String token) {
    return sessions.findByToken(token)
        .orElseThrow(() -> new MwException(ErrorCodes.VAL_NOT_FOUND, "Not found").withStatus(HttpStatus.NOT_FOUND));
  }

  /** 410 once the pay window has passed, 409 when the session is no longer OPEN. */
  private static void requireOpen(CheckoutSession s, Instant now) {
    SessionStatus st = s.statusAt(now);
    if (st == SessionStatus.EXPIRED) {
      throw new MwException(ErrorCodes.VAL_SESSION_EXPIRED, "This checkout session has expired")
          .withStatus(HttpStatus.GONE);
    }
    if (st != SessionStatus.OPEN) {
      throw new MwException(ErrorCodes.VAL_SESSION_STATE, "This checkout session is " + st.name().toLowerCase())
          .withStatus(HttpStatus.CONFLICT);
    }
  }

  private String merchantName(CheckoutSession s) {
    CheckoutProperties.Merchant m = props.merchant(s.merchantId());
    return m == null ? s.merchantId() : m.name();
  }

  private static MwException otpLimit() {
    return new MwException(ErrorCodes.VAL_OTP_LIMIT, "No more OTPs for this session")
        .withStatus(HttpStatus.TOO_MANY_REQUESTS);
  }

  private static String correlationId() {
    String id = MDC.get("correlationId");
    return id == null || id.isBlank() ? UUID.randomUUID().toString() : id;
  }
}
