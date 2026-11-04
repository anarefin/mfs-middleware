package mfs.mw.adapter.billpay.messaging;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import mfs.mw.adapter.billpay.config.MwServiceProperties;
import mfs.mw.adapter.billpay.dto.AuditEvent;
import mfs.mw.adapter.billpay.partner.template.PartnerAudit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The adapter framework's audit sink (spec 12.2, 17): every partner call the wrapper records
 * becomes an {@link AuditEvent} on {@code mw.audit.event.v1}, through the outbox in a transaction
 * of its own (the call happened whatever becomes of the caller's transaction). Action {@code
 * partner.call.completed}, actor {@code system}/<this service>, resource {@code
 * partner/<partnerId>/<operation>}, outcome SUCCESS when the partner answered SUCCESS or PENDING,
 * else FAILURE; {@code after} holds the call's facts and the request and response bodies exactly
 * as {@link PartnerAudit} masked them. Key: txnId, else the partner id.
 */
@Component
public class PartnerAuditPublisher implements Consumer<PartnerAudit.Entry> {

  public static final String AUDIT_TOPIC = "mw.audit.event.v1";
  public static final String ACTION = "partner.call.completed";

  private final OutboxWriter outbox;
  private final TransactionTemplate tx;
  private final MwServiceProperties service;
  private final Clock clock;

  public PartnerAuditPublisher(
      OutboxWriter outbox, PlatformTransactionManager transactions, MwServiceProperties service, Clock clock) {
    this.outbox = outbox;
    this.tx = new TransactionTemplate(transactions);
    this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.service = service;
    this.clock = clock;
  }

  @Override
  public void accept(PartnerAudit.Entry entry) {
    AuditEvent event = toEvent(entry);
    String key = event.txnId() != null ? event.txnId() : entry.partnerId();
    tx.executeWithoutResult(
        s -> outbox.append(AUDIT_TOPIC, key, new EventMetadata(event.correlationId(), event.txnId(), event.auditId(), 1), event));
  }

  AuditEvent toEvent(PartnerAudit.Entry e) {
    Map<String, String> after = new LinkedHashMap<>();
    put(after, "partnerInstance", e.instance());
    put(after, "httpMethod", e.method());
    put(after, "path", e.path());
    put(after, "httpStatus", e.httpStatus() == null ? null : String.valueOf(e.httpStatus()));
    put(after, "partnerOutcome", e.outcome());
    put(after, "durationMs", String.valueOf(e.durationMs()));
    put(after, "request", e.request());
    put(after, "response", e.response());
    String correlationId =
        e.correlationId() != null && !e.correlationId().isBlank()
            ? e.correlationId()
            : e.txnId() != null ? e.txnId() : e.partnerId();
    return new AuditEvent(
        1,
        UUID.randomUUID().toString(),
        e.txnId(),
        correlationId,
        new AuditEvent.Actor("system", service.name()),
        ACTION,
        "partner/" + e.partnerId() + "/" + e.operation(),
        null,
        after,
        "SUCCESS".equals(e.outcome()) || "PENDING".equals(e.outcome()) ? "SUCCESS" : "FAILURE",
        clock.instant(),
        service.name());
  }

  private static void put(Map<String, String> map, String key, String value) {
    if (value != null) {
      map.put(key, value);
    }
  }
}
