package mfs.mw.app.gateway.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import mfs.mw.app.gateway.config.AppGatewayProperties;
import mfs.mw.app.gateway.dto.AppUser;
import mfs.mw.app.gateway.dto.ErrorDetail;
import mfs.mw.app.gateway.dto.ErrorInfo;
import mfs.mw.app.gateway.dto.StatusMessage;
import mfs.mw.app.gateway.dto.SubmitResult;
import mfs.mw.app.gateway.dto.TransactionRequest;
import mfs.mw.app.gateway.dto.TransactionView;
import mfs.mw.app.gateway.dto.TxnRequest;
import mfs.mw.app.gateway.exception.ErrorCodes;
import mfs.mw.app.gateway.exception.MwException;
import mfs.mw.app.gateway.mapper.RequestMapper;
import mfs.mw.app.gateway.messaging.EventMetadata;
import mfs.mw.app.gateway.messaging.EventPublisher;
import mfs.mw.app.gateway.messaging.MwHeaders;
import mfs.mw.app.gateway.repository.AppTransactionRepository;
import mfs.mw.app.gateway.util.UuidV7;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * App transactions (spec 9.1, 7.5): mints the txnId, publishes the canonical request with this
 * instance's {@code mw-reply-to}, then waits on the HTTP request's own (virtual) thread for the
 * answer, at most the reply budget. Idempotency itself is decided by mw-orchestrator (7.3); this
 * service only requires the key and passes it on.
 */
@Service
public class TransactionService {

  private static final Logger log = LoggerFactory.getLogger(TransactionService.class);

  private final EventPublisher publisher;
  private final ReplyWaiters waiters;
  private final RequestMapper mapper;
  private final AppTransactionRepository transactions;
  private final AppGatewayProperties props;
  private final Clock clock;

  public TransactionService(
      EventPublisher publisher,
      ReplyWaiters waiters,
      RequestMapper mapper,
      AppTransactionRepository transactions,
      AppGatewayProperties props,
      Clock clock) {
    this.publisher = publisher;
    this.waiters = waiters;
    this.mapper = mapper;
    this.transactions = transactions;
    this.props = props;
    this.clock = clock;
  }

  public SubmitResult submit(AppUser user, String idempotencyKey, TransactionRequest body) {
    if (!props.supportedTxnTypes().contains(body.txnType())) {
      throw new MwException(ErrorCodes.VAL_UNSUPPORTED_TXN_TYPE, "Transaction type is not supported");
    }
    refuseUnknownAttributes(body);
    refuseFieldsOfOtherFlows(body);
    Instant now = clock.instant();
    String txnId = UuidV7.next(clock).toString();
    String correlationId = correlationId();
    TxnRequest req = mapper.toRequest(user, idempotencyKey, body, txnId, correlationId, now);

    CompletableFuture<StatusMessage> answer =
        waiters.register(txnId, idempotencyKey, req.initiator().id());
    try {
      publisher.publish(
          props.requestTopic(), txnId, new EventMetadata(correlationId, txnId, idempotencyKey, 1),
          req, Map.of(MwHeaders.REPLY_TO, props.instanceId()));
    } catch (RuntimeException e) {
      waiters.remove(txnId);
      throw new MwException(ErrorCodes.SYS_UNAVAILABLE, "Service temporarily unavailable, try again", e)
          .withStatus(HttpStatus.SERVICE_UNAVAILABLE);
    }
    transactions.insertAccepted(user.msisdn(), user.channel(), mapper.accepted(req), now);
    log.atInfo()
        .addKeyValue("event", "txn.request.published")
        .addKeyValue("txnId", txnId)
        .addKeyValue("channel", user.channel())
        .log("transaction request published");

    StatusMessage status = await(answer, txnId);
    if (status == null) {
      return SubmitResult.processing(TransactionView.processing(txnId));
    }
    return answerFor(status);
  }

  /** Only allow-listed attribute keys (MW-VAL-001 naming each other key). */
  private void refuseUnknownAttributes(TransactionRequest body) {
    if (body.attributes() == null) {
      return;
    }
    List<ErrorDetail> refused =
        body.attributes().keySet().stream()
            .filter(k -> !props.allowedAttributes().contains(k))
            .sorted()
            .map(k -> new ErrorDetail("attributes." + k, "is not allowed"))
            .toList();
    if (!refused.isEmpty()) {
      throw new MwException(ErrorCodes.VAL_INVALID, "Request validation failed").withDetails(refused);
    }
  }

  /**
   * TOPUP needs {@code partner} and recharges an MSISDN ({@code creditParty.type} absent or
   * {@code MSISDN}); every other type pays a wallet and takes no partner (MW-VAL-001 naming the
   * field).
   */
  private static void refuseFieldsOfOtherFlows(TransactionRequest body) {
    boolean topup = RequestMapper.TOPUP.equals(body.txnType());
    String type = body.creditParty().type();
    String issue = null;
    String field = null;
    if (topup && body.partner() == null) {
      field = "partner";
      issue = "is required for TOPUP";
    } else if (!topup && body.partner() != null) {
      field = "partner";
      issue = "is only allowed for TOPUP";
    } else if (type != null && !type.equals(topup ? "MSISDN" : "WALLET")) {
      field = "creditParty.type";
      issue = "must be " + (topup ? "MSISDN" : "WALLET") + " for " + body.txnType();
    }
    if (field != null) {
      throw new MwException(ErrorCodes.VAL_INVALID, "Request validation failed")
          .withDetails(List.of(new ErrorDetail(field, issue)));
    }
  }

  public TransactionView find(AppUser user, String txnId) {
    UUID id;
    try {
      id = UUID.fromString(txnId);
    } catch (IllegalArgumentException e) {
      throw notFound();
    }
    return transactions.find(id, user.msisdn()).orElseThrow(TransactionService::notFound);
  }

  /** The answer, or null when the budget ran out. Runs on the HTTP request thread only. */
  private StatusMessage await(CompletableFuture<StatusMessage> answer, String txnId) {
    try {
      return answer.get(props.replyBudget().toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      log.atInfo()
          .addKeyValue("event", "txn.reply.timeout")
          .addKeyValue("txnId", txnId)
          .log("no terminal status within the reply budget, answering 202");
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

  /**
   * A duplicate answer that is still running is 202 under the stored txnId; COMPLETED/REVERSED
   * is 200; FAILED/REJECTED is the error envelope with the status's error code and txnId.
   */
  private static SubmitResult answerFor(StatusMessage s) {
    if (!s.terminal()) {
      return SubmitResult.processing(TransactionView.processing(s.txnId()));
    }
    ErrorInfo error = s.error();
    if (("FAILED".equals(s.status()) || "REJECTED".equals(s.status())) && error != null) {
      MwException e =
          new MwException(error.code(), error.message())
              .withTxnId(s.txnId())
              .withRetryable(error.retryable());
      throw error.messageBn() == null ? e : e.withMessageBn(error.messageBn());
    }
    return SubmitResult.done(TransactionView.of(s));
  }

  private static String correlationId() {
    String id = MDC.get("correlationId");
    return id == null || id.isBlank() ? UUID.randomUUID().toString() : id;
  }

  private static MwException notFound() {
    return new MwException(ErrorCodes.VAL_NOT_FOUND, "Not found").withStatus(HttpStatus.NOT_FOUND);
  }
}
