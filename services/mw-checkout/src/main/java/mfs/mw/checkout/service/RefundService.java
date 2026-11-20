package mfs.mw.checkout.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import mfs.mw.checkout.config.CheckoutProperties;
import mfs.mw.checkout.dto.Amount;
import mfs.mw.checkout.dto.RefundList;
import mfs.mw.checkout.dto.RefundRequest;
import mfs.mw.checkout.dto.RefundView;
import mfs.mw.checkout.dto.SessionStatus;
import mfs.mw.checkout.dto.StatusMessage;
import mfs.mw.checkout.dto.TxnRequest;
import mfs.mw.checkout.entity.CheckoutSession;
import mfs.mw.checkout.entity.Refund;
import mfs.mw.checkout.exception.ErrorCategory;
import mfs.mw.checkout.exception.ErrorCodes;
import mfs.mw.checkout.exception.MwException;
import mfs.mw.checkout.mapper.CheckoutMapper;
import mfs.mw.checkout.mapper.RefundMapper;
import mfs.mw.checkout.messaging.EventMetadata;
import mfs.mw.checkout.messaging.EventPublisher;
import mfs.mw.checkout.messaging.MwHeaders;
import mfs.mw.checkout.repository.RefundRepository;
import mfs.mw.checkout.repository.SessionRepository;
import mfs.mw.checkout.util.UuidV7;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Merchant refunds of a paid checkout payment (ticket 116, FRS CH-WEB-03, FL-06, UC-12). A refund is
 * its own REVERSAL transaction (its own txnId = refundId, its own idempotency key); the orchestrator
 * checks ownership, parties and amount, the core keeps the refunded total and refuses past the
 * payment ({@code MW-CORE-REFUND_EXCEEDS}). Here: the merchant owns the payment (403 MW-AUTH-022), the
 * payment is PAID (409 MW-VAL-021), and the merchant's Idempotency-Key gives one refund per key (same
 * body: the stored refund; another body: 409 MW-IDEM-001). The request goes out with {@code mw-reply-to}
 * and is waited for within the reply budget like the payment ({@link ReplyWaiters}).
 */
@Service
public class RefundService {

  private static final Logger log = LoggerFactory.getLogger(RefundService.class);

  /** What the create answered: the refund and whether this call made it. */
  public record Created(RefundView refund, boolean isNew) {}

  private final SessionRepository sessions;
  private final RefundRepository refunds;
  private final RefundOutcomes outcomes;
  private final RefundMapper mapper;
  private final EventPublisher publisher;
  private final ReplyWaiters waiters;
  private final CheckoutProperties props;
  private final Clock clock;

  public RefundService(
      SessionRepository sessions, RefundRepository refunds, RefundOutcomes outcomes, RefundMapper mapper,
      EventPublisher publisher, ReplyWaiters waiters, CheckoutProperties props, Clock clock) {
    this.sessions = sessions;
    this.refunds = refunds;
    this.outcomes = outcomes;
    this.mapper = mapper;
    this.publisher = publisher;
    this.waiters = waiters;
    this.props = props;
    this.clock = clock;
  }

  public Created create(String merchantId, String paymentId, String idemKey, RefundRequest body) {
    requireMerchant(merchantId);
    if (idemKey == null || !idemKey.matches("[A-Za-z0-9._:-]{1,64}")) {
      throw new MwException(ErrorCodes.VAL_INVALID, "Idempotency-Key header is required (1-64 of A-Z a-z 0-9 . _ : -)");
    }
    CheckoutSession s = owned(merchantId, paymentId);
    long amount = body == null || body.amount() == null ? s.amountMinor() : body.amount().valueMinor();
    String reason = body == null ? null : body.reason();
    String hash = hash(s.sessionId(), amount, reason);

    Refund existing = refunds.findByKey(merchantId, idemKey).orElse(null);
    if (existing != null) {
      return new Created(repeat(existing, hash, s), false);
    }
    if (s.status() != SessionStatus.PAID || s.txnId() == null) {
      throw new MwException(ErrorCodes.VAL_SESSION_STATE, "Only a paid payment can be refunded")
          .withStatus(HttpStatus.CONFLICT);
    }
    Instant now = clock.instant();
    Refund r = new Refund(UuidV7.next(clock), s.sessionId(), merchantId, idemKey, hash, amount, s.currency(),
        s.txnId(), reason, Refund.Status.PENDING, null, now, now);
    if (!refunds.insert(r)) {
      // the same key at the same moment from another request: answer as a repeat
      return new Created(repeat(refunds.findByKey(merchantId, idemKey).orElseThrow(), hash, s), false);
    }
    String correlationId = correlationId();
    TxnRequest txn = mapper.toTxnRequest(r, s, correlationId, now);
    CompletableFuture<StatusMessage> answer =
        waiters.register(txn.txnId(), txn.idempotencyKey(), txn.initiator().id());
    try {
      publisher.publish(
          props.requestTopic(), txn.txnId(),
          new EventMetadata(correlationId, txn.txnId(), txn.idempotencyKey(), 1), txn,
          Map.of(MwHeaders.REPLY_TO, props.instanceId()));
    } catch (RuntimeException e) {
      waiters.remove(txn.txnId());
      refunds.deleteUnsent(r.refundId());
      throw new MwException(ErrorCodes.SYS_UNAVAILABLE, "Service temporarily unavailable, try again", e)
          .withStatus(HttpStatus.SERVICE_UNAVAILABLE);
    }
    log.atInfo()
        .addKeyValue("event", "txn.request.published")
        .addKeyValue("txnId", txn.txnId())
        .addKeyValue("channel", CheckoutMapper.CHANNEL)
        .addKeyValue("sessionId", s.sessionId().toString())
        .log("checkout refund request published");
    StatusMessage status = await(answer, txn.txnId());
    if (status != null && status.terminal()) {
      outcomes.apply(status);
    }
    Refund after = refunds.findById(r.refundId()).orElseThrow();
    return new Created(answerOf(after, s), true);
  }

  public RefundList list(String merchantId, String paymentId) {
    requireMerchant(merchantId);
    CheckoutSession s = owned(merchantId, paymentId);
    List<Refund> rows = refunds.findBySession(s.sessionId());
    long total = rows.stream().filter(r -> r.status() == Refund.Status.COMPLETED).mapToLong(Refund::amountMinor).sum();
    return new RefundList(rows.stream().map(r -> mapper.view(r, s)).toList(), new Amount(total, s.currency()));
  }

  /** Remaining refundable amount given the refunds so far (COMPLETED and PENDING count). */
  public static long remaining(long paymentMinor, List<Refund> refunds) {
    long used = refunds.stream()
        .filter(r -> r.status() != Refund.Status.REJECTED)
        .mapToLong(Refund::amountMinor)
        .sum();
    return Math.max(0, paymentMinor - used);
  }

  private RefundView repeat(Refund existing, String hash, CheckoutSession s) {
    if (!existing.requestHash().equals(hash)) {
      throw new MwException(ErrorCodes.IDEM_KEY_CONFLICT, "This Idempotency-Key was used for another refund")
          .withStatus(HttpStatus.CONFLICT);
    }
    return answerOf(existing, s);
  }

  /** A REJECTED refund answers with its refusal (the orchestrator's or the core's code), else the view. */
  private RefundView answerOf(Refund r, CheckoutSession s) {
    if (r.status() == Refund.Status.REJECTED) {
      String code = r.errorCode() == null ? ErrorCodes.SYS_INTERNAL : r.errorCode();
      MwException e = new MwException(code, "The refund was refused").withTxnId(r.refundId().toString());
      if (code.startsWith("MW-AUTH-")) {
        e = e.withStatus(HttpStatus.FORBIDDEN);
      } else if (ErrorCategory.of(code) == ErrorCategory.SYS) {
        e = e.withStatus(HttpStatus.UNPROCESSABLE_CONTENT).withRetryable(false);
      }
      throw e;
    }
    return mapper.view(r, s);
  }

  private CheckoutSession owned(String merchantId, String paymentId) {
    UUID id;
    try {
      id = UUID.fromString(paymentId);
    } catch (IllegalArgumentException e) {
      throw notFound();
    }
    CheckoutSession s = sessions.findById(id).orElseThrow(RefundService::notFound);
    if (!s.merchantId().equals(merchantId)) {
      throw new MwException(ErrorCodes.AUTH_NOT_OWNER, "Not this merchant's payment").withStatus(HttpStatus.FORBIDDEN);
    }
    return s;
  }

  private void requireMerchant(String merchantId) {
    if (merchantId == null || merchantId.isBlank()) {
      throw new MwException(ErrorCodes.AUTH_NO_MERCHANT, "Merchant authentication required");
    }
    if (props.merchant(merchantId) == null) {
      throw new MwException(ErrorCodes.AUTH_UNKNOWN_MERCHANT, "Not a checkout merchant")
          .withStatus(HttpStatus.FORBIDDEN);
    }
  }

  private StatusMessage await(CompletableFuture<StatusMessage> answer, String txnId) {
    try {
      return answer.get(props.replyBudget().toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      log.atInfo()
          .addKeyValue("event", "txn.reply.timeout")
          .addKeyValue("txnId", txnId)
          .log("no final refund status within the reply budget, answering 202");
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

  static String hash(UUID sessionId, long amount, String reason) {
    try {
      MessageDigest d = MessageDigest.getInstance("SHA-256");
      String text = sessionId + "\n" + amount + "\n" + (reason == null ? "" : reason);
      return HexFormat.of().formatHex(d.digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static MwException notFound() {
    return new MwException(ErrorCodes.VAL_NOT_FOUND, "Not found").withStatus(HttpStatus.NOT_FOUND);
  }

  private static String correlationId() {
    String id = MDC.get("correlationId");
    return id == null || id.isBlank() ? UUID.randomUUID().toString() : id;
  }
}
