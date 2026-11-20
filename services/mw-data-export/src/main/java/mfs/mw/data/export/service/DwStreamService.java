package mfs.mw.data.export.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import mfs.mw.data.export.dto.NotifyStatusEvent;
import mfs.mw.data.export.dto.TxnStatusEvent;
import mfs.mw.data.export.messaging.EventMetadata;
import mfs.mw.data.export.messaging.Inbox;
import mfs.mw.data.export.messaging.OutboxWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Projects a source event into the DW topics and the batch facts, once per event id (ticket 128).
 * The published body is the policy's output, and the fact row is filled from that body, so a clear
 * MSISDN is neither published nor stored.
 */
@Service
public class DwStreamService {

  private final DwEvents events;
  private final DwFactStore facts;
  private final OutboxWriter outbox;
  private final Inbox inbox;
  private final ZoneId zone;

  public DwStreamService(DwEvents events, DwFactStore facts, OutboxWriter outbox, Inbox inbox,
      ExportPropertiesZone zone) {
    this.events = events;
    this.facts = facts;
    this.outbox = outbox;
    this.inbox = inbox;
    this.zone = zone.zone();
  }

  /** The business day of an instant in the warehouse zone (Asia/Dhaka). */
  public LocalDate businessDate(Instant instant) {
    return LocalDate.ofInstant(instant, zone);
  }

  @Transactional
  public void onTxn(TxnStatusEvent event) {
    String eventId = event.txnId() + "|" + event.status() + "|" + event.updatedAt();
    inbox.processOnce("dw-txn", eventId, () -> {
      ListPublished published = publish(events.fromTxn(event), event.correlationId(), event.txnId(), eventId);
      Map<String, Object> body = published.state();
      facts.upsertTxn(
          event.txnId(),
          event.txnType(),
          event.status(),
          event.amount().valueMinor(),
          event.amount().currency(),
          event.channel(),
          String.valueOf(body.get("payerToken")),
          String.valueOf(body.get("payeeToken")),
          event.requestedAt(),
          event.completedAt(),
          businessDate(event.requestedAt()));
    });
  }

  @Transactional
  public void onNotify(NotifyStatusEvent event) {
    String eventId = event.messageId() + "|" + event.status() + "|" + event.at();
    inbox.processOnce("dw-notify", eventId, () -> {
      DwEvents.Outbound outbound = events.fromNotify(event);
      append(outbound, event.correlationId(), event.txnId() == null ? event.messageId() : event.txnId(), eventId);
      Object token = outbound.body().get("recipientToken");
      facts.upsertNotification(event, token == null ? "" : String.valueOf(token), businessDate(event.at()));
    });
  }

  @Transactional
  public void onRecon(Map<String, Object> fields) {
    String eventId = String.valueOf(fields.get("exceptionId"));
    inbox.processOnce("dw-recon", eventId, () -> {
      DwEvents.Outbound outbound = events.fromRecon(fields);
      append(outbound, String.valueOf(fields.get("correlationId")), String.valueOf(fields.get("txnId")), eventId);
      facts.upsertRecon(
          String.valueOf(outbound.body().get("exceptionId")),
          String.valueOf(outbound.body().get("exceptionClass")),
          String.valueOf(outbound.body().get("partner")),
          String.valueOf(outbound.body().get("txnId")),
          Long.parseLong(String.valueOf(outbound.body().get("amountMinor"))),
          String.valueOf(outbound.body().get("statementRef")),
          Instant.parse(String.valueOf(outbound.body().get("detectedAt"))),
          businessDate(Instant.parse(String.valueOf(outbound.body().get("detectedAt")))));
    });
  }

  @Transactional
  public void onKyc(Map<String, Object> fields) {
    String eventId = String.valueOf(fields.get("correlationId")) + "|" + fields.get("completedAt");
    inbox.processOnce("dw-kyc", eventId, () -> {
      DwEvents.Outbound outbound = events.fromKyc(fields);
      append(outbound, String.valueOf(fields.get("correlationId")), eventId, eventId);
    });
  }

  /** Projects an operator audit event. System and partner actors are not a warehouse operator action. */
  @Transactional
  public void onOperatorAudit(String auditId, String correlationId, String actorType, String actorId, String action,
      String resource, String outcome, Instant at) {
    if (!"user".equals(actorType)) {
      return;
    }
    inbox.processOnce("dw-audit", auditId, () -> {
      DwEvents.Outbound outbound = events.fromOperatorAudit(auditId, correlationId, actorId, action, resource, outcome, at);
      append(outbound, correlationId, auditId, auditId);
    });
  }

  private ListPublished publish(java.util.List<DwEvents.Outbound> outbound, String correlationId, String txnId,
      String eventId) {
    Map<String, Object> state = Map.of();
    for (DwEvents.Outbound one : outbound) {
      append(one, correlationId, txnId, eventId);
      if (DwTopics.TXN_STATE.equals(one.topic())) {
        state = one.body();
      }
    }
    return new ListPublished(state);
  }

  private void append(DwEvents.Outbound outbound, String correlationId, String txnId, String eventId) {
    String correlation = correlationId == null || correlationId.isBlank() ? eventId : correlationId;
    outbox.append(outbound.topic(), outbound.key(), new EventMetadata(correlation, txnId, eventId, 1), outbound.body());
  }

  private record ListPublished(Map<String, Object> state) {}
}
