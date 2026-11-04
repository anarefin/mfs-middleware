package mfs.mw.orchestrator.service;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import mfs.mw.orchestrator.dto.AuditEvent;
import mfs.mw.orchestrator.dto.ErrorInfo;
import mfs.mw.orchestrator.dto.PartnerInbound;
import mfs.mw.orchestrator.dto.PartnerReply;
import mfs.mw.orchestrator.entity.TransactionRecord;
import mfs.mw.orchestrator.mapper.AuditMapper;
import mfs.mw.orchestrator.mapper.PartnerReplyMapper;
import mfs.mw.orchestrator.mapper.PartnerRequestMapper;
import mfs.mw.orchestrator.messaging.EventMetadata;
import mfs.mw.orchestrator.messaging.Inbox;
import mfs.mw.orchestrator.messaging.OutboxWriter;
import mfs.mw.orchestrator.repository.TransactionRepository;
import mfs.mw.orchestrator.service.saga.Flow;
import mfs.mw.orchestrator.service.saga.FlowRegistry;
import mfs.mw.orchestrator.exception.InvalidTransitionException;
import mfs.mw.orchestrator.service.saga.PartnerCall;
import mfs.mw.orchestrator.service.saga.Outcome;
import mfs.mw.orchestrator.service.saga.SagaEvent;
import mfs.mw.orchestrator.service.saga.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes partner callbacks on {@code mw.partner.inbound.v1} (ticket 22, spec 11.2 inbound
 * path). A CALLBACK carries the final outcome of an earlier PAY the partner accepted as PENDING:
 *
 * <ul>
 *   <li>PARTNER_PENDING: SUCCESS commits the hold, FAILED releases it with the callback's error
 *       (the same events as a definite partner reply); PENDING changes nothing;
 *   <li>UNKNOWN: handed to {@link InquiryService} like a status inquiry answer;
 *   <li>any other state (the deadline, a status inquiry or an earlier callback already resolved
 *       it): nothing changes, the callback is recorded on the audit trail ({@code
 *       partner.callback.late}, or {@code txn.outcome.conflict} with outcome FAILURE when it
 *       contradicts the resolution, also logged as an error for operations and reconciliation).
 * </ul>
 *
 * Ownership first: a callback is applied only when its partner, family and (once stored) partner
 * reference match the transaction, the flow has a partner leg and the outcome is SUCCESS, FAILED
 * or PENDING; otherwise nothing changes and it is recorded as {@code partner.callback.rejected}
 * (outcome DENIED, event {@code security.partner_callback_mismatch}).
 *
 * <p>Effectively once: the inbox drops a redelivered {@code inboundId} (the adapter derives it from
 * partner, reference and outcome, so a duplicate callback has the same one). REMITTANCE and
 * NPSB_CREDIT (new inbound money, ticket 23) are not handled yet: logged and dropped.
 */
@Service
public class PartnerInboundService {

  static final String CONSUMER = "orchestrator.partner-inbound";
  static final String CALLBACK = "CALLBACK";
  static final java.util.Set<Outcome> OUTCOMES = java.util.EnumSet.of(Outcome.SUCCESS, Outcome.FAILED, Outcome.PENDING);

  private static final Logger log = LoggerFactory.getLogger(PartnerInboundService.class);

  private final Inbox inbox;
  private final TransactionRepository transactions;
  private final PartnerReplyMapper mapper;
  private final SagaEngine engine;
  private final InquiryService inquiries;
  private final AuditMapper audits;
  private final OutboxWriter outbox;
  private final FlowRegistry flows;
  private final Clock clock;

  public PartnerInboundService(
      Inbox inbox,
      TransactionRepository transactions,
      PartnerReplyMapper mapper,
      SagaEngine engine,
      InquiryService inquiries,
      AuditMapper audits,
      OutboxWriter outbox,
      FlowRegistry flows,
      Clock clock) {
    this.inbox = inbox;
    this.transactions = transactions;
    this.mapper = mapper;
    this.engine = engine;
    this.inquiries = inquiries;
    this.audits = audits;
    this.outbox = outbox;
    this.flows = flows;
    this.clock = clock;
  }

  @Transactional
  public void handle(PartnerInbound inbound) {
    String kind = inbound.payload() == null ? null : inbound.payload().kind();
    if (!CALLBACK.equals(kind)) {
      drop(inbound, "partner.inbound.unsupported", "inbound partner message of a kind not handled yet; dropped");
      return;
    }
    inbox.processOnce(CONSUMER, inbound.inboundId(), () -> apply(inbound));
  }

  private void apply(PartnerInbound inbound) {
    Optional<TransactionRecord> found = transactions.findForUpdate(UUID.fromString(inbound.txnId()));
    if (found.isEmpty()) {
      drop(inbound, "txn.unknown", "partner callback for an unknown transaction");
      return;
    }
    TransactionRecord rec = found.get();
    Optional<String> mismatch = mismatch(rec, inbound);
    if (mismatch.isPresent()) {
      reject(rec, inbound, mismatch.get());
      return;
    }
    PartnerReply answer = asReply(inbound);
    switch (rec.status()) {
      case UNKNOWN -> inquiries.partnerAnswer(rec, answer);
      case PARTNER_PENDING -> resolvePending(rec, answer);
      default -> late(rec, inbound);
    }
  }

  private void resolvePending(TransactionRecord rec, PartnerReply answer) {
    Optional<SagaEvent> saga = mapper.toSagaEvent(answer, rec.holdsFunds(flows));
    if (saga.isEmpty()) {
      log.atInfo()
          .addKeyValue("event", "partner.callback.pending")
          .addKeyValue("txnId", answer.txnId())
          .addKeyValue("partnerId", answer.partnerId())
          .log("partner callback without a final outcome; nothing changes");
      return;
    }
    ErrorInfo error = saga.get() instanceof SagaEvent.PartnerFailed ? mapper.error(answer) : null;
    try {
      engine.fire(rec.withPartnerRef(answer.payload().partnerRef()), saga.get(), error);
    } catch (InvalidTransitionException e) {
      // PartnerUnknown cannot come from a callback; every other answer fits PARTNER_PENDING
      log.atWarn()
          .addKeyValue("event", "saga.event.ignored")
          .addKeyValue("txnId", answer.txnId())
          .addKeyValue("status", e.from().name())
          .log("partner callback does not fit the transaction's state; ignored");
    }
  }

  /**
   * Why the callback does not belong to this transaction, if it does not (security review,
   * ticket 22): a valid signature only proves which partner sent it, so the partner, the family,
   * the flow's partner leg and, once one is stored, the partner reference must all match the
   * transaction; and only SUCCESS, FAILED and PENDING are outcomes. The reference matches when it
   * is the stored partner reference or our own reference for this transaction ({@code UPAY} + its
   * txnId, from which the adapter resolved the txnId).
   */
  private Optional<String> mismatch(TransactionRecord rec, PartnerInbound in) {
    if (Outcome.parse(in.payload().outcome()).filter(OUTCOMES::contains).isEmpty()) {
      return Optional.of("UNKNOWN_OUTCOME");
    }
    Optional<PartnerCall> leg = flows.find(rec.request().txnType()).flatMap(Flow::partnerCall);
    String partnerId = SagaEngine.partnerId(rec);
    if (leg.isEmpty() || partnerId == null) {
      return Optional.of("NO_PARTNER_LEG");
    }
    if (!partnerId.equals(in.partnerId())) {
      return Optional.of("PARTNER_MISMATCH");
    }
    if (!leg.get().family().equals(in.family())) {
      return Optional.of("FAMILY_MISMATCH");
    }
    String stored = rec.partnerRef();
    String ours = PartnerRequestMapper.partnerTxnRef(rec.txnId().toString());
    if (stored != null && !stored.isBlank() && !stored.equals(in.partnerRef()) && !ours.equals(in.partnerRef())) {
      return Optional.of("PARTNER_REF_MISMATCH");
    }
    return Optional.empty();
  }

  /** Not this transaction's callback: nothing changes; security event and a DENIED audit record. */
  private void reject(TransactionRecord rec, PartnerInbound inbound, String reason) {
    AuditEvent e =
        audits.callbackRejected(
            rec, inbound.partnerId(), inbound.correlationId(), inbound.payload().outcome(), reason, clock.instant());
    outbox.append(
        SagaEngine.AUDIT_TOPIC, e.txnId(), new EventMetadata(e.correlationId(), e.txnId(), e.auditId(), 1), e);
    log.atWarn()
        .addKeyValue("event", "security.partner_callback_mismatch")
        .addKeyValue("txnId", inbound.txnId())
        .addKeyValue("partnerId", inbound.partnerId())
        .addKeyValue("family", inbound.family())
        .addKeyValue("status", rec.status().name())
        .addKeyValue("reason", reason)
        .log("partner callback does not belong to the transaction; rejected, nothing changes");
  }

  /** The transaction left PARTNER_PENDING / UNKNOWN before the callback came: record only. */
  private void late(TransactionRecord rec, PartnerInbound inbound) {
    String outcome = inbound.payload().outcome();
    String errorCode = inbound.payload().errorCode();
    boolean conflict = Outcome.parse(outcome).map(o -> o.contradicts(rec.status())).orElse(false);
    AuditEvent e =
        audits.lateCallback(
            rec, inbound.partnerId(), inbound.correlationId(), outcome, errorCode, conflict, clock.instant());
    outbox.append(
        SagaEngine.AUDIT_TOPIC, e.txnId(), new EventMetadata(e.correlationId(), e.txnId(), e.auditId(), 1), e);
    var event = conflict ? log.atError() : log.atInfo();
    event
        .addKeyValue("event", conflict ? AuditMapper.OUTCOME_CONFLICT : AuditMapper.LATE_CALLBACK)
        .addKeyValue("txnId", inbound.txnId())
        .addKeyValue("partnerId", inbound.partnerId())
        .addKeyValue("status", rec.status().name())
        .addKeyValue("outcome", outcome)
        .log(conflict
            ? "partner callback contradicts the transaction's resolution; state kept, recorded for operations"
            : "partner callback after the transaction was resolved; recorded, nothing changes");
  }

  /** The callback in the shape of a definite partner reply (operation CALLBACK). */
  private static PartnerReply asReply(PartnerInbound in) {
    PartnerInbound.Payload p = in.payload();
    String message = p.attributes() == null ? null : p.attributes().get("message");
    return new PartnerReply(
        in.schemaVersion(),
        in.family(),
        in.inboundId(),
        in.txnId(),
        in.correlationId(),
        in.partnerId(),
        in.receivedAt(),
        new PartnerReply.Payload(
            CALLBACK, p.outcome(), in.partnerRef(), p.partnerCode(), p.errorCode(), message, false, p.attributes()));
  }

  private static void drop(PartnerInbound inbound, String name, String message) {
    log.atInfo()
        .addKeyValue("event", name)
        .addKeyValue("txnId", inbound.txnId())
        .addKeyValue("partnerId", inbound.partnerId())
        .log(message);
  }
}
