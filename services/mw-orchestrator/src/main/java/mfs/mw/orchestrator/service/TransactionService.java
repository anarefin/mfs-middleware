package mfs.mw.orchestrator.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import mfs.mw.orchestrator.config.OrchestratorProperties;
import mfs.mw.orchestrator.dto.Amount;
import mfs.mw.orchestrator.dto.ErrorInfo;
import mfs.mw.orchestrator.dto.PartnerRef;
import mfs.mw.orchestrator.dto.Party;
import mfs.mw.orchestrator.dto.StatusMessage;
import mfs.mw.orchestrator.dto.TxnRequest;
import mfs.mw.orchestrator.entity.TransactionRecord;
import mfs.mw.orchestrator.exception.ErrorCodes;
import mfs.mw.orchestrator.mapper.RequestMapper;
import mfs.mw.orchestrator.mapper.StatusMapper;
import mfs.mw.orchestrator.repository.IdempotencyRepository;
import mfs.mw.orchestrator.repository.TransactionRepository;
import mfs.mw.orchestrator.service.routing.Attempt;
import mfs.mw.orchestrator.service.routing.Decision;
import mfs.mw.orchestrator.service.routing.RoutingCatalog;
import mfs.mw.orchestrator.service.routing.RoutingService;
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
 * Intake of {@code mw.txn.request.v1} (spec 7.3): takes the Redis idempotency lock
 * ({@link IdempotencyGate}, rule 4), records the idempotency key, creates the transaction in
 * RECEIVED and starts its flow. Everything happens in one database transaction
 * together with the outbox rows, so a request is either fully accepted or not at all.
 */
@Service
public class TransactionService {

  private static final Logger log = LoggerFactory.getLogger(TransactionService.class);

  static final String REVERSAL = "REVERSAL";
  static final String BULK_DISBURSE = "BULK_DISBURSE";

  /**
   * What {@code core-wallet.v1} {@code Party} can express. {@code PARTNER} is an app role, not a
   * core wallet type; an organisation debit is party type {@code ORG} (stored as ORGANISATION).
   */
  private static final Set<String> CORE_PARTY_TYPES =
      Set.of("WALLET", "BANK_ACCOUNT", "CARD_TOKEN", "BILLER_ACCOUNT", "MSISDN", "MERCHANT", "ORG");
  private static final Set<String> CORE_WALLET_TYPES =
      Set.of("CUSTOMER", "AGENT", "MERCHANT", "DISTRIBUTOR", "DSO");

  private final IdempotencyRepository idempotency;
  private final TransactionRepository transactions;
  private final RequestMapper requestMapper;
  private final StatusMapper statusMapper;
  private final FlowRegistry flows;
  private final SagaEngine engine;
  private final IdempotencyGate gate;
  private final DuplicateGuard duplicates;
  private final OrchestratorProperties properties;
  private final RoutingCatalog routing;
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
      RoutingCatalog routing,
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
    this.routing = routing;
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

    // 7.3 rule 4: the Redis lock absorbs a concurrent double-tap before the claim.
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

    ErrorInfo refusal = refusal(req);
    TxnRequest storedReq = refusal == null ? withChosenPartner(req) : req;
    TransactionRecord rec = requestMapper.toRecord(storedReq, replyTo, now);
    transactions.insert(rec);
    engine.created(rec);

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
   * request (holding the Redis lock) has not committed its row yet.
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
    boolean partnerFirst = flows.find(original.request().txnType())
        .filter(f -> f.needsHold() && f.partnerCall().isPresent())
        .isPresent();
    engine.fire(
        original.withReversal(UUID.fromString(req.txnId())),
        new SagaEvent.ReversalRequested(partnerFirst),
        null);
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
    ErrorInfo unexpressible = unexpressible(req);
    if (unexpressible != null) {
      return unexpressible;
    }
    if (BULK_DISBURSE.equals(req.txnType()) && !properties.bulkChannels().contains(req.channel())) {
      // no PIN on a bulk child: only the org portal (maker-checker) may send one (ticket 34)
      return new ErrorInfo(
          ErrorCodes.AUTH_BULK_CHANNEL, "This channel may not send a bulk disbursement", null, false);
    }
    if (flow.get().partnerCall().isPresent()) {
      ErrorInfo partner = partnerRefusal(req);
      if (partner != null) {
        return partner;
      }
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
   * A party or amount {@code core-wallet.v1} cannot express is refused here, before validate and
   * therefore before any hold. Wallet types are the core {@code Party.walletType} enum; currency
   * is BDT in integer poisha.
   */
  private static ErrorInfo unexpressible(TxnRequest req) {
    Amount amount = req.amount();
    if (amount == null || amount.valueMinor() < 0 || !Amount.BDT.equals(amount.currency())) {
      return new ErrorInfo(ErrorCodes.VAL_AMOUNT, "The core cannot post this amount", null, false);
    }
    ErrorInfo debit = unexpressible(req.debitParty());
    return debit != null ? debit : unexpressible(req.creditParty());
  }

  private static ErrorInfo unexpressible(Party party) {
    if (party == null
        || party.type() == null
        || !CORE_PARTY_TYPES.contains(party.type())
        || party.id() == null
        || party.id().isBlank()
        || party.id().length() > 64
        || (party.walletType() != null && !CORE_WALLET_TYPES.contains(party.walletType()))) {
      return new ErrorInfo(ErrorCodes.VAL_INVALID, "The core cannot post this party", null, false);
    }
    return null;
  }

  /**
   * A partner flow (ticket 98). A top-up without a partner id is routed from the MSISDN. A bill still
   * needs its biller and add-money still needs its bank. An unknown partner, a disabled biller and a
   * connector that cannot be called are refused here, before any money is held.
   */
  private ErrorInfo partnerRefusal(TxnRequest req) {
    if ("BILL_PAY".equals(req.txnType())
        && (req.partner() == null || req.partner().billerCode() == null || req.partner().billerCode().isBlank())) {
      return new ErrorInfo(ErrorCodes.VAL_PARTNER_REQUIRED, "A biller is required for this transaction type", null, false);
    }
    if ("ADD_MONEY_BANK".equals(req.txnType())
        && (req.partner() == null || req.partner().partnerId() == null || req.partner().partnerId().isBlank())) {
      return new ErrorInfo(ErrorCodes.VAL_PARTNER_REQUIRED, "A partner bank is required for this transaction type", null, false);
    }
    if ("TOPUP".equals(req.txnType())
        && (req.partner() == null || req.partner().productCode() == null || req.partner().productCode().isBlank())) {
      return new ErrorInfo(ErrorCodes.VAL_PARTNER_REQUIRED, "A product is required for this transaction type", null, false);
    }
    return switch (routing.decide(req)) {
      case Decision.UnknownPartner ignored -> new ErrorInfo(ErrorCodes.PTR_UNKNOWN, "Unknown partner", null, false);
      case Decision.Blocked blocked ->
          new ErrorInfo(
              blocked.code(), blocked.message(), blocked.messageBn(), RoutingService.CIRCUIT_OPEN.equals(blocked.code()));
      case Decision.Ready ignored -> null;
    };
  }

  /** A top-up that named no partner keeps the connector routing chose, so later steps and the status query use it. */
  private TxnRequest withChosenPartner(TxnRequest req) {
    if (!(routing.decide(req) instanceof Decision.Ready ready)) {
      return req;
    }
    PartnerRef current = req.partner();
    if (current != null && current.partnerId() != null && !current.partnerId().isBlank()) {
      return req;
    }
    Attempt chosen = ready.attempts().get(0);
    return req.withPartner(
        new PartnerRef(chosen.wirePartnerId(), current == null ? null : current.productCode(), current == null ? null : current.billerCode()));
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
