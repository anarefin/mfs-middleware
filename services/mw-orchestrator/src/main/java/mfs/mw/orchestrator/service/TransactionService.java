package mfs.mw.orchestrator.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import mfs.mw.orchestrator.config.OrchestratorProperties;
import mfs.mw.orchestrator.dto.ErrorInfo;
import mfs.mw.orchestrator.dto.StatusMessage;
import mfs.mw.orchestrator.dto.TxnRequest;
import mfs.mw.orchestrator.entity.TransactionRecord;
import mfs.mw.orchestrator.exception.ErrorCodes;
import mfs.mw.orchestrator.mapper.RequestMapper;
import mfs.mw.orchestrator.mapper.StatusMapper;
import mfs.mw.orchestrator.repository.IdempotencyRepository;
import mfs.mw.orchestrator.repository.TransactionRepository;
import mfs.mw.orchestrator.service.saga.Flow;
import mfs.mw.orchestrator.service.saga.FlowRegistry;
import mfs.mw.orchestrator.service.saga.SagaEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Intake of {@code mw.txn.request.v1} (spec 7.3): records the idempotency key, creates the
 * transaction in RECEIVED and starts its flow. Everything happens in one database transaction
 * together with the outbox rows, so a request is either fully accepted or not at all.
 */
@Service
public class TransactionService {

  private static final Logger log = LoggerFactory.getLogger(TransactionService.class);

  private final IdempotencyRepository idempotency;
  private final TransactionRepository transactions;
  private final RequestMapper requestMapper;
  private final StatusMapper statusMapper;
  private final FlowRegistry flows;
  private final SagaEngine engine;
  private final OrchestratorProperties properties;
  private final Clock clock;

  public TransactionService(
      IdempotencyRepository idempotency,
      TransactionRepository transactions,
      RequestMapper requestMapper,
      StatusMapper statusMapper,
      FlowRegistry flows,
      SagaEngine engine,
      OrchestratorProperties properties,
      Clock clock) {
    this.idempotency = idempotency;
    this.transactions = transactions;
    this.requestMapper = requestMapper;
    this.statusMapper = statusMapper;
    this.flows = flows;
    this.engine = engine;
    this.properties = properties;
    this.clock = clock;
  }

  /**
   * @param replyTo value of the {@code mw-reply-to} header (null for channels that poll), copied
   *     to every status record of the transaction
   */
  @Transactional
  public void accept(TxnRequest req, String replyTo) {
    Instant now = clock.instant();
    UUID txnId = UUID.fromString(req.txnId());
    byte[] hash = requestMapper.requestHash(req);

    Optional<IdempotencyRepository.Stored> earlier =
        idempotency.claim(
            req.channel(), req.initiator().id(), req.idempotencyKey(), hash, txnId, now,
            now.plus(properties.idempotencyTtl()));
    if (earlier.isPresent()) {
      answerRepeat(req, earlier.get(), hash, replyTo, now);
      return;
    }

    TransactionRecord rec = requestMapper.toRecord(req, replyTo, now);
    transactions.insert(rec);
    engine.created(rec);

    ErrorInfo refusal = refusal(req);
    if (refusal != null) {
      engine.fire(rec, new SagaEvent.RequestRejected(), refusal);
    } else {
      engine.fire(rec, new SagaEvent.ValidationStarted(), null);
    }
  }

  /** The key was seen before: same content gets the stored status, other content MW-IDEM-001. */
  private void answerRepeat(
      TxnRequest req, IdempotencyRepository.Stored stored, byte[] hash, String replyTo, Instant now) {
    if (stored.sameRequest(hash)) {
      TransactionRecord existing = transactions.find(stored.txnId()).orElseThrow();
      engine.publishStatus(statusMapper.toDuplicateStatus(existing, req.txnId()), replyTo);
      log.atInfo()
          .addKeyValue("event", "txn.idempotent.replay")
          .addKeyValue("txnId", stored.txnId().toString())
          .addKeyValue("correlationId", req.correlationId())
          .log("repeated request answered with the stored transaction");
      return;
    }
    StatusMessage refused =
        statusMapper.toRefusedStatus(
            req,
            new ErrorInfo(
                ErrorCodes.IDEM_CONFLICT,
                "Idempotency key was already used with a different request",
                null,
                false),
            now);
    engine.publishStatus(refused, replyTo);
    log.atWarn()
        .addKeyValue("event", "txn.idempotent.conflict")
        .addKeyValue("txnId", req.txnId())
        .addKeyValue("correlationId", req.correlationId())
        .addKeyValue("errorCode", ErrorCodes.IDEM_CONFLICT)
        .log("idempotency key reused with a different request");
  }

  private ErrorInfo refusal(TxnRequest req) {
    Optional<Flow> flow = flows.find(req.txnType());
    if (flow.isEmpty()) {
      return new ErrorInfo(
          ErrorCodes.VAL_UNSUPPORTED_TXN_TYPE, "Transaction type is not supported", null, false);
    }
    if (flow.get().partnerCall().isPresent()
        && (req.partner() == null || req.partner().partnerId() == null || req.partner().productCode() == null)) {
      return new ErrorInfo(
          ErrorCodes.VAL_PARTNER_REQUIRED, "Partner and product are required for this transaction type", null, false);
    }
    if (req.amount().valueMinor() <= 0) {
      return new ErrorInfo(ErrorCodes.VAL_AMOUNT, "Amount must be greater than zero", null, false);
    }
    return null;
  }
}
