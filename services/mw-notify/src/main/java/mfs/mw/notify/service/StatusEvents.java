package mfs.mw.notify.service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import mfs.mw.notify.dto.AuditEvent;
import mfs.mw.notify.dto.Channel;
import mfs.mw.notify.dto.NotifyStatus;
import mfs.mw.notify.dto.NotifyStatusEvent;
import mfs.mw.notify.entity.MessageRecord;
import mfs.mw.notify.messaging.EventMetadata;
import mfs.mw.notify.messaging.OutboxWriter;
import mfs.mw.notify.util.MaskingPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Writes {@code mw.notify.status.v1} to the outbox in the caller's transaction (spec 6.4, 10.4):
 * every attempt and every final status. A message without a txnId (an OTP) uses its messageId for
 * the {@code mw-txn-id} header. A final status of a transaction's message also writes its
 * NOTIFICATION audit hop on {@code mw.audit.event.v1} (ticket 130b) in the same transaction.
 */
@Component
public class StatusEvents {

  public static final String TOPIC = "mw.notify.status.v1";
  public static final String AUDIT_TOPIC = "mw.audit.event.v1";
  public static final String AUDIT_ACTION = "notification.completed";
  /** The timeline hop of a notification (ticket 130b). */
  public static final String HOP = "NOTIFICATION";

  private static final String SERVICE = "mw-notify";
  private static final int MAX_BODY = 4096;
  private static final Set<NotifyStatus> SUCCESS = Set.of(NotifyStatus.SENT, NotifyStatus.DELIVERED, NotifyStatus.READ);
  private static final Pattern TXN_ID =
      Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");

  private static final Logger log = LoggerFactory.getLogger(StatusEvents.class);

  private final OutboxWriter outbox;

  public StatusEvents(OutboxWriter outbox) {
    this.outbox = outbox;
  }

  public void publish(MessageRecord m, NotifyStatus status, boolean isFinal, int attempt, String providerMessageId,
      String errorCode, Instant at) {
    int shownAttempt = Math.max(1, attempt);
    NotifyStatusEvent event = new NotifyStatusEvent(1, m.messageId(), m.txnId(), m.correlationId(),
        Channel.valueOf(m.channel()), status, isFinal, shownAttempt, m.templateCode(), providerMessageId,
        errorCode, at);
    String correlation = blankTo(m.correlationId(), m.messageId());
    EventMetadata meta = new EventMetadata(correlation, blankTo(m.txnId(), m.messageId()),
        idempotencyKey(m, status, shownAttempt), 1);
    outbox.append(TOPIC, m.messageId(), meta, event);
    if (isFinal && m.txnId() != null && TXN_ID.matcher(m.txnId()).matches()) {
      AuditEvent audit = audit(m, status, shownAttempt, errorCode, correlation, at);
      outbox.append(AUDIT_TOPIC, m.txnId(), new EventMetadata(correlation, m.txnId(), audit.auditId(), 1), audit);
    }
    log.atInfo()
        .addKeyValue("event", "notify.status")
        .addKeyValue("messageId", m.messageId())
        .addKeyValue("txnId", m.txnId())
        .addKeyValue("channel", m.channel())
        .addKeyValue("correlationId", correlation)
        .addKeyValue("status", status)
        .addKeyValue("final", isFinal)
        .addKeyValue("attempt", shownAttempt)
        .addKeyValue("errorCode", errorCode)
        .log("notification status");
  }

  /**
   * The NOTIFICATION hop of a final status (ticket 130b, FRS AUD-01, AUD-04). The rendered text is
   * the payload only for a transaction notice (priority TXN): an OTP message is the code itself, so
   * its text never goes to the audit trail; the email HTML part is left out.
   */
  private static AuditEvent audit(
      MessageRecord m, NotifyStatus status, int attempt, String errorCode, String correlation, Instant at) {
    Map<String, String> after = new HashMap<>();
    after.put("messageId", m.messageId());
    after.put("channel", m.channel());
    after.put("status", status.name());
    after.put("attempt", Integer.toString(attempt));
    if (m.templateCode() != null) {
      after.put("templateCode", m.templateCode());
    }
    if (errorCode != null) {
      after.put("errorCode", errorCode);
    }
    List<AuditEvent.Payload> payload =
        "TXN".equals(m.priority()) && m.body() != null && !m.body().isBlank()
            ? List.of(new AuditEvent.Payload("OUT", "text/plain", excerpt(MaskingPolicy.scrub(m.body()))))
            : null;
    return new AuditEvent(
        1,
        UUID.randomUUID().toString(),
        m.txnId(),
        correlation,
        new AuditEvent.Actor("system", SERVICE),
        AUDIT_ACTION,
        "txn/" + m.txnId(),
        after,
        SUCCESS.contains(status) ? "SUCCESS" : "FAILURE",
        at,
        SERVICE,
        HOP,
        "OUT",
        payload);
  }

  private static String excerpt(String text) {
    return text.length() <= MAX_BODY ? text : text.substring(0, MAX_BODY);
  }

  /** One per (message, channel, status, attempt); SMS keeps its ticket 24 form. */
  static String idempotencyKey(MessageRecord m, NotifyStatus status, int attempt) {
    String channel = Channel.SMS.name().equals(m.channel()) ? "" : m.channel() + ":";
    return m.messageId() + ":" + channel + status + ":" + attempt;
  }

  private static String blankTo(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }
}
