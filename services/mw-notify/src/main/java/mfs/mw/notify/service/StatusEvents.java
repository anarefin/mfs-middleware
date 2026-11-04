package mfs.mw.notify.service;

import java.time.Instant;
import mfs.mw.notify.dto.Channel;
import mfs.mw.notify.dto.NotifyStatus;
import mfs.mw.notify.dto.NotifyStatusEvent;
import mfs.mw.notify.entity.MessageRecord;
import mfs.mw.notify.messaging.EventMetadata;
import mfs.mw.notify.messaging.OutboxWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Writes {@code mw.notify.status.v1} to the outbox in the caller's transaction (spec 6.4, 10.4):
 * every attempt and every final status. A message without a txnId (an OTP) uses its messageId for
 * the {@code mw-txn-id} header.
 */
@Component
public class StatusEvents {

  public static final String TOPIC = "mw.notify.status.v1";

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
        m.messageId() + ":" + status + ":" + shownAttempt, 1);
    outbox.append(TOPIC, m.messageId(), meta, event);
    log.atInfo()
        .addKeyValue("event", "notify.status")
        .addKeyValue("messageId", m.messageId())
        .addKeyValue("txnId", m.txnId())
        .addKeyValue("correlationId", correlation)
        .addKeyValue("status", status)
        .addKeyValue("final", isFinal)
        .addKeyValue("attempt", shownAttempt)
        .addKeyValue("errorCode", errorCode)
        .log("notification status");
  }

  private static String blankTo(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }
}
