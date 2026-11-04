package mfs.mw.checkout.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import mfs.mw.checkout.config.CheckoutProperties;
import mfs.mw.checkout.dto.Amount;
import mfs.mw.checkout.dto.ConfirmRequest;
import mfs.mw.checkout.dto.ErrorInfo;
import mfs.mw.checkout.dto.NotificationRequest;
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
import mfs.mw.checkout.messaging.OutboxWriter;
import mfs.mw.checkout.repository.SessionRepository;
import mfs.mw.checkout.util.MaskingPolicy;
import mfs.mw.checkout.util.Msisdn;
import mfs.mw.checkout.util.OtpCodes;
import mfs.mw.checkout.util.SensitiveType;
import mfs.mw.checkout.util.UuidV7;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The hosted page API (spec 9.3 step 2): the page holds only the pay token from the checkout URL.
 * The customer names the paying wallet, gets an OTP by SMS (through mw-notify, OTP priority),
 * then confirms with the OTP and the PIN ciphertext; the service submits one MERCHANT_PAY per
 * session to mw-orchestrator and waits, on the HTTP request's own (virtual) thread, at most the
 * reply budget (spec 7.5). Every state change is a conditional update, so a race cannot pay twice.
 */
@Service
public class CheckoutPayService {

  private static final Logger log = LoggerFactory.getLogger(CheckoutPayService.class);

  private final SessionRepository sessions;
  private final CheckoutProperties props;
  private final CheckoutMapper mapper;
  private final OutboxWriter outbox;
  private final EventPublisher publisher;
  private final ReplyWaiters waiters;
  private final SessionOutcomes outcomes;
  private final ResultLinks links;
  private final Clock clock;

  public CheckoutPayService(
      SessionRepository sessions,
      CheckoutProperties props,
      CheckoutMapper mapper,
      OutboxWriter outbox,
      EventPublisher publisher,
      ReplyWaiters waiters,
      SessionOutcomes outcomes,
      ResultLinks links,
      Clock clock) {
    this.sessions = sessions;
    this.props = props;
    this.mapper = mapper;
    this.outbox = outbox;
    this.publisher = publisher;
    this.waiters = waiters;
    this.outcomes = outcomes;
    this.links = links;
    this.clock = clock;
  }

  public PageView page(String token) {
    CheckoutSession s = byToken(token);
    SessionStatus status = s.statusAt(clock.instant());
    return new PageView(
        merchantName(s), s.orderId(), s.description(), new Amount(s.amountMinor(), s.currency()), status,
        s.expiresAt(), s.payerMsisdn() == null ? null : MaskingPolicy.mask(SensitiveType.MSISDN, s.payerMsisdn()),
        new PageView.PinKey(props.pin().keyId(), props.pin().publicKey()),
        s.txnId() == null ? null : s.txnId().toString(), links.redirectUrl(s));
  }

  /** A new OTP to {@code wallet}, sent through the outbox with the session change. */
  @Transactional
  public OtpSent sendOtp(String token, String wallet) {
    CheckoutSession s = byToken(token);
    Instant now = clock.instant();
    requireOpen(s, now);
    if (s.otpSends() >= props.otpMaxSends()) {
      throw otpLimit();
    }
    String payer = Msisdn.canonical(wallet);
    String otp = OtpCodes.newOtp();
    Instant otpExpiry = now.plus(props.otpTtl());
    if (!sessions.otpIssued(s.sessionId(), payer, OtpCodes.hash(s.sessionId(), otp), otpExpiry, props.otpMaxSends(), now)) {
      // a concurrent request changed the session in between
      CheckoutSession again = byToken(token);
      requireOpen(again, now);
      throw otpLimit();
    }
    CheckoutSession updated = byToken(token);
    String correlationId = correlationId();
    NotificationRequest sms =
        mapper.toOtpNotification(updated, merchantName(s), otp, updated.otpSends(), otpExpiry, correlationId);
    // no transaction yet: the 6.1 txn and idempotency headers carry the messageId (as mw-notify does)
    outbox.append(
        props.notifyTopic(), payer, new EventMetadata(correlationId, sms.messageId(), sms.messageId(), 1), sms);
    log.atInfo()
        .addKeyValue("event", "checkout.otp.sent")
        .addKeyValue("sessionId", s.sessionId().toString())
        .log("checkout OTP requested");
    return new OtpSent(
        MaskingPolicy.mask(SensitiveType.MSISDN, payer), otpExpiry, props.otpMaxSends() - updated.otpSends());
  }

  /** Checks the OTP, then submits the MERCHANT_PAY and waits for its outcome within the budget. */
  public PayResult confirm(String token, ConfirmRequest req) {
    CheckoutSession s = byToken(token);
    Instant now = clock.instant();
    requireOpen(s, now);
    int max = props.otpMaxAttempts();
    if (s.otpAttempts() >= max) {
      throw otpLocked();
    }
    if (s.otpHash() == null || s.otpExpiresAt() == null || !now.isBefore(s.otpExpiresAt())) {
      throw new MwException(ErrorCodes.AUTH_OTP_EXPIRED, "Request a new OTP");
    }
    // Every guess takes one of the OTP's checks atomically before it is compared: parallel
    // guesses cannot get more than max comparisons (a row count of 0 means none is left).
    if (!sessions.reserveOtpAttempt(s.sessionId(), s.otpHash(), max, now)) {
      throw refusedCheck(token, now);
    }
    String hash = OtpCodes.hash(s.sessionId(), req.otp());
    if (!OtpCodes.matches(s.otpHash(), hash)) {
      sessions.voidOtpIfExhausted(s.sessionId(), s.otpHash(), max, now);
      log.atInfo()
          .addKeyValue("event", "checkout.otp.wrong")
          .addKeyValue("sessionId", s.sessionId().toString())
          .log("wrong checkout OTP");
      throw new MwException(ErrorCodes.AUTH_OTP_WRONG, "Wrong OTP");
    }
    UUID txnId = UuidV7.next(clock);
    if (!sessions.claimForPayment(s.sessionId(), s.otpHash(), txnId, max, now)) {
      throw refusedCheck(token, now);
    }
    CheckoutSession paying = byToken(token);
    String correlationId = correlationId();
    TxnRequest txn = mapper.toTxnRequest(paying, txnId.toString(), correlationId, req.encryptedPin(), now);
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

  /**
   * Why an atomic OTP check or claim was refused: the session state first (410/409), then no
   * check left on the OTP (locked), else the OTP was replaced or spent meanwhile.
   */
  private MwException refusedCheck(String token, Instant now) {
    CheckoutSession again = byToken(token);
    requireOpen(again, now);
    if (again.otpAttempts() >= props.otpMaxAttempts()) {
      return otpLocked();
    }
    return new MwException(ErrorCodes.AUTH_OTP_EXPIRED, "Request a new OTP");
  }

  private static MwException otpLocked() {
    return new MwException(ErrorCodes.AUTH_OTP_LOCKED, "Too many wrong OTPs, request a new one");
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
