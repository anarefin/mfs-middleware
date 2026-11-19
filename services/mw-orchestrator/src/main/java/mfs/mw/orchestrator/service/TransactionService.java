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
import mfs.mw.orchestrator.service.saga.BalanceFlow;
import mfs.mw.orchestrator.service.saga.SagaEvent;
import mfs.mw.orchestrator.service.saga.Status;
import mfs.mw.orchestrator.service.saga.Step;
import mfs.mw.orchestrator.util.SearchKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Intake of {@code mw.txn.request.v1} (spec 7.3): takes the Valkey idempotency lock
 * ({@link IdempotencyGate}, rule 4), records the idempotency key, creates the transaction in
 * RECEIVED and starts its flow. Everything happens in one database transaction
 * together with the outbox rows, so a request is either fully accepted or not at all.
 */
@Service
public class TransactionService {

  private static final Logger log = LoggerFactory.getLogger(TransactionService.class);

  static final String REVERSAL = "REVERSAL";
  static final String BULK_DISBURSE = "BULK_DISBURSE";

  private final IdempotencyRepository idempotency;
  private final TransactionRepository transactions;
  private final RequestMapper requestMapper;
  private final StatusMapper statusMapper;
  private final FlowRegistry flows;
  private final SagaEngine engine;
  private final IdempotencyGate gate;
  private final DuplicateGuard duplicates;
  private final OrchestratorProperties properties;
  private final Clock clock;

  public TransactionService(
      IdempotencyRepository idempotency,
      TransactionRepository transactions,
      RequestMapper requestMapper,
      StatusMapper statusMapper,
      FlowRegistry flows,
      SagaEngine engine,
      IdempotencyGate gate,
      DuplicateGuard duplicates,
      OrchestratorProperties properties,
      Clock clock) {
    this.idempotency = idempotency;
    this.transactions = transactions;
    this.requestMapper = requestMapper;
    this.statusMapper = statusMapper;
    this.flows = flows;
    this.engine = engine;
    this.gate = gate;
    this.duplicates = duplicates;
    this.properties = properties;
    this.clock = clock;
  }

  /**
   * @param replyTo value of the {@code mw-reply-to} header (null for channels that poll), copied
   *     to every status record of the transaction
   */
  @Transactional
  public void accept(TxnRequest req, String replyTo) {
    if (REVERSAL.equals(req.txnType())) {
      acceptReversal(req, replyTo);
      return;
    }
    Instant now = clock.instant();
    UUID txnId = UUID.fromString(req.txnId());
    byte[] hash = requestMapper.requestHash(req);

    // FRS PLT-013: the same payment under another key within the window. Before the lock and the
    // claim, so a refused request records nothing and its key stays free for the confirmed resend.
    switch (duplicates.check(req)) {
      case PROMPT -> {
        refuseDuplicate(req, replyTo, now, ErrorCodes.VAL_DUPLICATE_SUSPECTED,
            "The same transaction was sent moments ago; confirm to send it again",
            "একই লেনদেন কিছুক্ষণ আগে পাঠানো হয়েছে; আবার পাঠাতে নিশ্চিত করুন");
        return;
      }
      case REJECT -> {
        refuseDuplicate(req, replyTo, now, ErrorCodes.VAL_DUPLICATE_REJECTED,
            "The same transaction was sent moments ago under another idempotency key", null);
        return;
      }
      case PASS -> {
        // on to the idempotency lock
      }
    }

    // 7.3 rule 4: the Valkey lock absorbs a concurrent double-tap before the claim.
    switch (gate.enter(req.channel(), req.initiator().id(), req.idempotencyKey(), txnId, hash)) {
      case IdempotencyGate.Decision.Duplicate d -> {
        answerDuplicate(req, d.holderTxnId(), replyTo, now, "lock");
        return;
      }
      case IdempotencyGate.Decision.Conflict c -> {
        answerConflict(req, replyTo, now);
        return;
      }
      case IdempotencyGate.Decision.Proceed p -> {
        // on to the primary key
      }
    }

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
    } else if (flows.find(req.txnType()).orElseThrow().allows(Step.BALANCE_INQUIRY)) {
      engine.fire(rec, new SagaEvent.BalanceInquiryStarted(), null); // ADR D26: no money moves
    } else {
      engine.fire(rec, new SagaEvent.ValidationStarted(), null);
    }
  }

  /** The key was seen before: same content gets the stored status, other content MW-IDEM-001. */
  private void answerRepeat(
      TxnRequest req, IdempotencyRepository.Stored stored, byte[] hash, String replyTo, Instant now) {
    if (stored.sameRequest(hash)) {
      answerDuplicate(req, stored.txnId(), replyTo, now, "primary-key");
    } else {
      answerConflict(req, replyTo, now);
    }
  }

  /**
   * Same key and content as {@code storedTxnId}: its current status, or RECEIVED while the first
   * request (holding the Valkey lock) has not committed its row yet.
   */
  private void answerDuplicate(TxnRequest req, UUID storedTxnId, String replyTo, Instant now, String source) {
    StatusMessage status =
        transactions
            .find(storedTxnId)
            .map(existing -> statusMapper.toDuplicateStatus(existing, req.txnId()))
            .orElseGet(() -> statusMapper.toInFlightDuplicateStatus(req, storedTxnId, now));
    engine.publishStatus(status, replyTo);
    log.atInfo()
        .addKeyValue("event", "txn.idempotent.replay")
        .addKeyValue("txnId", storedTxnId.toString())
        .addKeyValue("correlationId", req.correlationId())
        .addKeyValue("source", source)
        .log("repeated request answered with the stored transaction");
  }

  private void answerConflict(TxnRequest req, String replyTo, Instant now) {
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

  private void refuseDuplicate(
      TxnRequest req, String replyTo, Instant now, String code, String message, String messageBn) {
    engine.publishStatus(statusMapper.toRefusedStatus(req, new ErrorInfo(code, message, messageBn, false), now), replyTo);
    log.atWarn()
        .addKeyValue("event", "txn.duplicate.suspected")
        .addKeyValue("txnId", req.txnId())
        .addKeyValue("correlationId", req.correlationId())
        .addKeyValue("channel", req.channel())
        .addKeyValue("errorCode", code)
        .log("business duplicate within the window; refused without a record");
  }

  // --- REVERSAL (ticket 19, spec 7.2) ---------------------------------------------------------

  /**
   * A REVERSAL request does not become a transaction of its own: it moves the ORIGINAL from
   * COMPLETED to REVERSING (spec 7.2), and the core books the compensation under the request's
   * txnId. The original's row is locked first, so two reversals of one transaction serialise.
   * Refused (REJECTED status under the request's txnId, nothing recorded, key not consumed): a
   * channel that may not reverse, an unknown original, an original that is not COMPLETED, an
   * amount other than the original's (no partial reversal). A second reversal of a transaction
   * already reversing or reversed, under any key, is answered with the original's current status
   * and changes nothing.
   */
  private void acceptReversal(TxnRequest req, String replyTo) {
    Instant now = clock.instant();
    if (!properties.reversalChannels().contains(req.channel())) {
      refuse(req, replyTo, now, ErrorCodes.AUTH_REVERSAL_CHANNEL, "This channel may not request a reversal");
      return;
    }
    Optional<TransactionRecord> found = originalId(req).flatMap(transactions::findForUpdate);
    if (found.isEmpty()) {
      refuse(req, replyTo, now, ErrorCodes.VAL_ORIGINAL_NOT_FOUND, "Original transaction not found");
      return;
    }
    TransactionRecord original = found.get();
    if (!flows.find(original.request().txnType()).map(f -> f.allows(Step.REVERSE)).orElse(true)) {
      // a BALANCE check moved no money (ADR D26): nothing to compensate
      refuse(req, replyTo, now, ErrorCodes.VAL_NOT_REVERSIBLE, "This transaction cannot be reversed");
      return;
    }
    if (original.reversalTxnId() == null) {
      if (original.status() != Status.COMPLETED) {
        refuse(req, replyTo, now, ErrorCodes.VAL_NOT_REVERSIBLE, "Only a completed transaction can be reversed");
        return;
      }
      if (req.amount().valueMinor() != original.request().amount().valueMinor()) {
        refuse(req, replyTo, now, ErrorCodes.VAL_REVERSAL_AMOUNT, "Reversal amount must equal the original amount");
        return;
      }
    }

    byte[] hash = requestMapper.requestHash(req);
    Optional<IdempotencyRepository.Stored> earlier =
        idempotency.claim(
            req.channel(), req.initiator().id(), req.idempotencyKey(), hash, original.txnId(), now,
            now.plus(properties.idempotencyTtl()));
    if (earlier.isPresent()) {
      answerRepeat(req, earlier.get(), hash, replyTo, now);
      return;
    }
    if (original.reversalTxnId() != null) {
      engine.publishStatus(statusMapper.toDuplicateStatus(original, req.txnId()), replyTo);
      log.atInfo()
          .addKeyValue("event", "txn.reversal.repeated")
          .addKeyValue("txnId", original.txnId().toString())
          .addKeyValue("correlationId", req.correlationId())
          .log("transaction is already being reversed; answered with its current status");
      return;
    }
    engine.fire(original.withReversal(UUID.fromString(req.txnId())), new SagaEvent.ReversalRequested(), null);
  }

  private static Optional<UUID> originalId(TxnRequest req) {
    try {
      return req.originalTxnId() == null ? Optional.empty() : Optional.of(UUID.fromString(req.originalTxnId()));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  private void refuse(TxnRequest req, String replyTo, Instant now, String code, String message) {
    engine.publishStatus(statusMapper.toRefusedStatus(req, new ErrorInfo(code, message, null, false), now), replyTo);
    log.atWarn()
        .addKeyValue("event", "txn.reversal.refused")
        .addKeyValue("txnId", req.txnId())
        .addKeyValue("correlationId", req.correlationId())
        .addKeyValue("errorCode", code)
        .log("reversal request refused");
  }

  private ErrorInfo refusal(TxnRequest req) {
    Optional<Flow> flow = flows.find(req.txnType());
    if (flow.isEmpty()) {
      return new ErrorInfo(
          ErrorCodes.VAL_UNSUPPORTED_TXN_TYPE, "Transaction type is not supported", null, false);
    }
    if (BULK_DISBURSE.equals(req.txnType()) && !properties.bulkChannels().contains(req.channel())) {
      // no PIN on a bulk child: only the org portal (maker-checker) may send one (ticket 34)
      return new ErrorInfo(
          ErrorCodes.AUTH_BULK_CHANNEL, "This channel may not send a bulk disbursement", null, false);
    }
    if (flow.get().partnerCall().isPresent()
        && (req.partner() == null || req.partner().partnerId() == null || req.partner().productCode() == null)) {
      return new ErrorInfo(
          ErrorCodes.VAL_PARTNER_REQUIRED, "Partner and product are required for this transaction type", null, false);
    }
    if (BalanceFlow.TXN_TYPE.equals(req.txnType())) {
      return balanceRefusal(req);
    }
    if (req.amount().valueMinor() <= 0) {
      return new ErrorInfo(ErrorCodes.VAL_AMOUNT, "Amount must be greater than zero", null, false);
    }
    return null;
  }

  /**
   * BALANCE (ADR D26) moves no money: amount 0 only, the customer's own wallet (type WALLET) on
   * both sides (the same wallet in any MSISDN form), and mw-auth's assertion, which the core checks
   * as for a transfer. Anything else is refused before the core is asked.
   */
  private static ErrorInfo balanceRefusal(TxnRequest req) {
    if (req.amount().valueMinor() != 0) {
      return new ErrorInfo(ErrorCodes.VAL_AMOUNT, "Amount must be zero for a balance inquiry", null, false);
    }
    if (!SearchKeys.sameWallet(req.debitParty(), req.creditParty())) {
      return new ErrorInfo(
          ErrorCodes.VAL_INVALID, "A balance inquiry is for one wallet: debit and credit party must be the same wallet",
          null, false);
    }
    if (req.assertion() == null || req.assertion().isBlank()) {
      return new ErrorInfo(ErrorCodes.VAL_INVALID, "A balance inquiry needs the transaction assertion", null, false);
    }
    return null;
  }

}
