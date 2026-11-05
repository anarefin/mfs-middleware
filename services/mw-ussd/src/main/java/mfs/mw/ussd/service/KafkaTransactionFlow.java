package mfs.mw.ussd.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import mfs.mw.ussd.config.UssdProperties;
import mfs.mw.ussd.dto.FlowOutcome;
import mfs.mw.ussd.dto.FlowRequest;
import mfs.mw.ussd.dto.StatusMessage;
import mfs.mw.ussd.dto.TerminalStatus;
import mfs.mw.ussd.dto.TxnRequest;
import mfs.mw.ussd.mapper.TxnRequestMapper;
import mfs.mw.ussd.messaging.EventMetadata;
import mfs.mw.ussd.messaging.EventPublisher;
import mfs.mw.ussd.messaging.MwHeaders;
import mfs.mw.ussd.util.UuidV7;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/**
 * Hands a USSD transaction to mw-orchestrator (spec 9.2, 7.5): derives the idempotency key (7.3
 * rule 1), mints the txnId, publishes the canonical request with this instance's {@code
 * mw-reply-to}, then waits on the MNO request's own (virtual) thread at most the 8 s budget. No
 * Kafka thread waits: the reply consumer only completes the future.
 */
@Service
public class KafkaTransactionFlow implements TransactionFlow {

  private static final Logger log = LoggerFactory.getLogger(KafkaTransactionFlow.class);

  private final EventPublisher publisher;
  private final ReplyWaiters waiters;
  private final TxnRequestMapper mapper;
  private final UssdProperties props;
  private final Clock clock;

  public KafkaTransactionFlow(
      EventPublisher publisher, ReplyWaiters waiters, TxnRequestMapper mapper, UssdProperties props, Clock clock) {
    this.publisher = publisher;
    this.waiters = waiters;
    this.mapper = mapper;
    this.props = props;
    this.clock = clock;
  }

  @Override
  public FlowOutcome submit(FlowRequest request) {
    String amountMinor = Long.toString(TxnRequestMapper.minor(request.fields().get(FlowRequest.AMOUNT)));
    String key =
        IdempotencyKeys.derive(
            TxnRequestMapper.CHANNEL,
            request.session().msisdn(),
            request.session().sessionId(),
            request.menuPath(),
            amountMinor,
            request.fields().get(FlowRequest.CREDIT_PARTY));
    Instant now = clock.instant();
    String txnId = UuidV7.next(clock).toString();
    String correlationId = correlationId();
    TxnRequest req = mapper.toRequest(request, txnId, key, correlationId, now);

    CompletableFuture<StatusMessage> answer = waiters.register(txnId, key, req.initiator().id());
    try {
      publisher.publish(
          props.requestTopic(), txnId, new EventMetadata(correlationId, txnId, key, 1), req,
          Map.of(MwHeaders.REPLY_TO, props.instanceId()));
    } catch (RuntimeException e) {
      waiters.remove(txnId);
      log.atWarn()
          .addKeyValue("event", "txn.request.failed")
          .addKeyValue("txnId", txnId)
          .addKeyValue("errorClass", e.getClass().getName())
          .log("transaction request could not be published");
      return new FlowOutcome(FlowOutcome.Kind.UNAVAILABLE, null, null);
    }
    log.atInfo()
        .addKeyValue("event", "txn.request.published")
        .addKeyValue("txnId", txnId)
        .addKeyValue("channel", TxnRequestMapper.CHANNEL)
        .log("transaction request published");

    StatusMessage status = await(answer, txnId);
    TerminalStatus end = status == null ? null : status.terminalStatus().orElse(null);
    if (end == null) {
      return new FlowOutcome(FlowOutcome.Kind.PROCESSING, status == null ? txnId : status.txnId(), null);
    }
    return end.success()
        ? new FlowOutcome(FlowOutcome.Kind.COMPLETED, status.txnId(), null)
        : new FlowOutcome(FlowOutcome.Kind.FAILED, status.txnId(), status.error());
  }

  private StatusMessage await(CompletableFuture<StatusMessage> answer, String txnId) {
    try {
      return answer.get(props.syncBudget().toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      log.atInfo()
          .addKeyValue("event", "txn.reply.timeout")
          .addKeyValue("txnId", txnId)
          .log("no final status within the USSD budget, ending with being processed");
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

  private static String correlationId() {
    String id = MDC.get("correlationId");
    return id == null || id.isBlank() ? UUID.randomUUID().toString() : id;
  }
}
