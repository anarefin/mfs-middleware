package mfs.mw.ussd.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import mfs.mw.ussd.client.AuthClient;
import mfs.mw.ussd.config.UssdProperties;
import mfs.mw.ussd.dto.ErrorDetail;
import mfs.mw.ussd.dto.ErrorEnvelope;
import mfs.mw.ussd.dto.ErrorInfo;
import mfs.mw.ussd.dto.FlowOutcome;
import mfs.mw.ussd.dto.FlowRequest;
import mfs.mw.ussd.dto.StatusMessage;
import mfs.mw.ussd.dto.TerminalStatus;
import mfs.mw.ussd.dto.TxnRequest;
import mfs.mw.ussd.exception.AuthRefusedException;
import mfs.mw.ussd.exception.ErrorCodes;
import mfs.mw.ussd.exception.MwException;
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
 * rule 1), mints the txnId, builds the canonical request, has mw-auth check the PIN and issue the
 * transaction assertion for exactly that request (ticket 72, D22), publishes it with the assertion
 * and this instance's {@code mw-reply-to}, then waits on the MNO request's own (virtual) thread at
 * most the 8 s budget. No Kafka thread waits: the reply consumer only completes the future.
 * mw-auth's refusals end the flow before anything is published: a wrong PIN is {@code WRONG_PIN}
 * (the engine asks again), a PIN or wallet lock {@code LOCKED}, any other refusal {@code FAILED}
 * with mw-auth's message; mw-auth unreachable is {@code UNAVAILABLE}. A PIN block mw-crypto refused
 * ({@code MW-SYS-051}, e.g. a rotated transport key) also drops the cached transport key.
 */
@Service
public class KafkaTransactionFlow implements TransactionFlow {

  private static final Logger log = LoggerFactory.getLogger(KafkaTransactionFlow.class);

  private final EventPublisher publisher;
  private final ReplyWaiters waiters;
  private final TxnRequestMapper mapper;
  private final AuthClient auth;
  private final PinTransport pins;
  private final UssdProperties props;
  private final Clock clock;

  public KafkaTransactionFlow(
      EventPublisher publisher,
      ReplyWaiters waiters,
      TxnRequestMapper mapper,
      AuthClient auth,
      PinTransport pins,
      UssdProperties props,
      Clock clock) {
    this.publisher = publisher;
    this.waiters = waiters;
    this.mapper = mapper;
    this.auth = auth;
    this.pins = pins;
    this.props = props;
    this.clock = clock;
  }

  /**
   * The request's idempotency key (spec 7.3 rule 1): channel, MSISDN, session, menu path, amount
   * and target, plus the session's start: an SMPP gateway reuses its session numbers 1..128
   * ({@code its_session_info}), so a later session with the same number and the same transfer is a
   * new transaction, never a replay of the earlier one. Every step of one session keeps the key.
   * A BALANCE (ADR D26) maps no fields, so its key adds the attempt's {@code txnId}: each check is a
   * new inquiry (another check in the same session, or after a wrong PIN, would otherwise replay the
   * first one and its old balance).
   */
  static String idempotencyKey(FlowRequest request, String txnId) {
    String amountMinor = Long.toString(TxnRequestMapper.minor(request.fields().get(FlowRequest.AMOUNT)));
    String session = request.session().sessionId() + "@"
        + (request.session().startedAt() == null ? "" : request.session().startedAt().toEpochMilli());
    if (TxnRequestMapper.BALANCE.equals(request.flow())) {
      return IdempotencyKeys.derive(
          TxnRequestMapper.CHANNEL, request.session().msisdn(), session, request.menuPath(), TxnRequestMapper.BALANCE,
          txnId);
    }
    return IdempotencyKeys.derive(
        TxnRequestMapper.CHANNEL,
        request.session().msisdn(),
        session,
        request.menuPath(),
        amountMinor,
        request.fields().get(FlowRequest.CREDIT_PARTY));
  }

  @Override
  public FlowOutcome submit(FlowRequest request) {
    Instant now = clock.instant();
    String txnId = UuidV7.next(clock).toString();
    String key = idempotencyKey(request, txnId);
    String correlationId = correlationId();
    TxnRequest unsigned = mapper.toRequest(request, txnId, key, correlationId, now);
    if (request.pin() == null) {
      throw new IllegalStateException("an ACTION is reached from its PIN input only");
    }
    String assertion;
    try {
      assertion = auth.authorise(mapper.authorisation(unsigned, request.pin())).assertion();
    } catch (AuthRefusedException e) {
      return refused(e, txnId);
    } catch (MwException e) {
      return new FlowOutcome(FlowOutcome.Kind.UNAVAILABLE, null, null);
    }
    TxnRequest req = unsigned.withAssertion(assertion);

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
        ? new FlowOutcome(FlowOutcome.Kind.COMPLETED, status.txnId(), null, null, status.balance())
        : new FlowOutcome(FlowOutcome.Kind.FAILED, status.txnId(), status.error());
  }

  /** mw-auth's refusal as the flow's outcome; nothing was published. */
  private FlowOutcome refused(AuthRefusedException e, String txnId) {
    ErrorEnvelope env = e.envelope();
    log.atInfo()
        .addKeyValue("event", "txn.authorisation.refused")
        .addKeyValue("txnId", txnId)
        .addKeyValue("errorCode", env.code())
        .log("mw-auth refused the transaction authorisation");
    ErrorInfo error = new ErrorInfo(env.code(), env.message(), env.messageBn(), env.retryable());
    return switch (env.code()) {
      case ErrorCodes.AUTH_WRONG_PIN -> new FlowOutcome(FlowOutcome.Kind.WRONG_PIN, null, error, detail(env, "triesLeft"));
      case ErrorCodes.AUTH_PIN_LOCKED, ErrorCodes.AUTH_PIN_LOCKED_PERMANENT, ErrorCodes.AUTH_WALLET_LOCKED ->
          new FlowOutcome(FlowOutcome.Kind.LOCKED, null, error);
      case ErrorCodes.SYS_CRYPTO_REFUSED -> {
        pins.refresh();
        yield new FlowOutcome(FlowOutcome.Kind.UNAVAILABLE, null, null);
      }
      default -> new FlowOutcome(FlowOutcome.Kind.FAILED, null, error);
    };
  }

  private static String detail(ErrorEnvelope env, String field) {
    return env.details().stream()
        .filter(d -> field.equals(d.field()))
        .map(ErrorDetail::issue)
        .findFirst()
        .orElse(null);
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
