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
import mfs.mw.orchestrator.messaging.EventMetadata;
import mfs.mw.orchestrator.messaging.Inbox;
import mfs.mw.orchestrator.messaging.OutboxWriter;
import mfs.mw.orchestrator.repository.TransactionRepository;
import mfs.mw.orchestrator.service.saga.InvalidTransitionException;
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
 * Effectively once: the inbox drops a redelivered {@code inboundId} (the adapter derives it from
 * partner, reference and outcome, so a duplicate callback has the same one). REMITTANCE and
 * NPSB_CREDIT (new inbound money, ticket 23) are not handled yet: logged and dropped.
 */
@Service
public class PartnerInboundService {

  static final String CONSUMER = "orchestrator.partner-inbound";
  static final String CALLBACK = "CALLBACK";

  private static final Logger log = LoggerFactory.getLogger(PartnerInboundService.class);

  private final Inbox inbox;
  private final TransactionRepository transactions;
  private final PartnerReplyMapper mapper;
  private final SagaEngine engine;
  private final InquiryService inquiries;
  private final AuditMapper audits;
  private final OutboxWriter outbox;
  private final Clock clock;

  public PartnerInboundService(
      Inbox inbox,
      TransactionRepository transactions,
      PartnerReplyMapper mapper,
      SagaEngine engine,
      InquiryService inquiries,
      AuditMapper audits,
      OutboxWriter outbox,
      Clock clock) {
    this.inbox = inbox;
    this.transactions = transactions;
    this.mapper = mapper;
    this.engine = engine;
    this.inquiries = inquiries;
    this.audits = audits;
    this.outbox = outbox;
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
    PartnerReply answer = asReply(inbound);
    switch (rec.status()) {
      case UNKNOWN -> inquiries.partnerAnswer(rec, answer);
      case PARTNER_PENDING -> resolvePending(rec, answer);
      default -> late(rec, inbound);
    }
  }

  private void resolvePending(TransactionRecord rec, PartnerReply answer) {
    Optional<SagaEvent> saga = mapper.toSagaEvent(answer);
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

  /** The transaction left PARTNER_PENDING / UNKNOWN before the callback came: record only. */
  private void late(TransactionRecord rec, PartnerInbound inbound) {
    String outcome = inbound.payload().outcome();
    String errorCode = inbound.payload().errorCode();
    boolean paid = switch (rec.status()) {
      case COMMITTING, COMPLETED, REVERSING, REVERSED -> true;
      default -> false;
    };
    boolean refunded = rec.status() == Status.RELEASING || rec.status() == Status.FAILED;
    boolean conflict = ("SUCCESS".equals(outcome) && refunded) || ("FAILED".equals(outcome) && paid);
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
