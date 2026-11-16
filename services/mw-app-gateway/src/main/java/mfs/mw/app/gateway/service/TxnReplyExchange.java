package mfs.mw.app.gateway.service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import mfs.mw.app.gateway.config.AppGatewayProperties;
import mfs.mw.app.gateway.dto.ErrorInfo;
import mfs.mw.app.gateway.dto.StatusMessage;
import mfs.mw.app.gateway.dto.TxnRequest;
import mfs.mw.app.gateway.exception.ErrorCodes;
import mfs.mw.app.gateway.exception.MwException;
import mfs.mw.app.gateway.messaging.EventMetadata;
import mfs.mw.app.gateway.messaging.EventPublisher;
import mfs.mw.app.gateway.messaging.MwHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;

/**
 * The request/reply over Kafka shared by {@link TransactionService} and {@link BalanceService}
 * (spec 7.5): registers the waiter, publishes the canonical request with this instance's {@code
 * mw-reply-to} (Kafka refusing: 503 MW-SYS-010, waiter removed), and waits on the HTTP request's
 * own thread for the answer, at most the reply budget. Not a bean: each service builds one from its
 * own collaborators.
 */
final class TxnReplyExchange {

  private static final Logger log = LoggerFactory.getLogger(TxnReplyExchange.class);

  private final EventPublisher publisher;
  private final ReplyWaiters waiters;
  private final AppGatewayProperties props;

  TxnReplyExchange(EventPublisher publisher, ReplyWaiters waiters, AppGatewayProperties props) {
    this.publisher = publisher;
    this.waiters = waiters;
    this.props = props;
  }

  /** Registers the waiter, then publishes {@code req}; the future completes with its answer. */
  CompletableFuture<StatusMessage> publish(TxnRequest req) {
    CompletableFuture<StatusMessage> answer =
        waiters.register(req.txnId(), req.idempotencyKey(), req.initiator().id());
    try {
      publisher.publish(
          props.requestTopic(), req.txnId(),
          new EventMetadata(req.correlationId(), req.txnId(), req.idempotencyKey(), 1),
          req, Map.of(MwHeaders.REPLY_TO, props.instanceId()));
    } catch (RuntimeException e) {
      waiters.remove(req.txnId());
      throw new MwException(ErrorCodes.SYS_UNAVAILABLE, "Service temporarily unavailable, try again", e)
          .withStatus(HttpStatus.SERVICE_UNAVAILABLE);
    }
    return answer;
  }

  /** The answer, or null when the budget ran out. Runs on the HTTP request thread only. */
  StatusMessage await(CompletableFuture<StatusMessage> answer, String txnId) {
    try {
      return answer.get(props.replyBudget().toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      log.atInfo()
          .addKeyValue("event", "txn.reply.timeout")
          .addKeyValue("txnId", txnId)
          .log("no terminal status within the reply budget");
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
   * A FAILED/REJECTED status as the error envelope with its code, messages and txnId (MW-CORE-*
   * 422, MW-IDEM-001 409, ...); returns for any other status.
   */
  static void throwIfRefused(StatusMessage s) {
    ErrorInfo error = s.error();
    if (("FAILED".equals(s.status()) || "REJECTED".equals(s.status())) && error != null) {
      MwException e =
          new MwException(error.code(), error.message())
              .withTxnId(s.txnId())
              .withRetryable(error.retryable());
      throw error.messageBn() == null ? e : e.withMessageBn(error.messageBn());
    }
  }

  /** The request's correlation id (MDC), or a new one. */
  static String correlationId() {
    String id = MDC.get("correlationId");
    return id == null || id.isBlank() ? UUID.randomUUID().toString() : id;
  }
}
