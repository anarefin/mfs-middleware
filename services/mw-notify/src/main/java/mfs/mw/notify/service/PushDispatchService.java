package mfs.mw.notify.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import mfs.mw.notify.client.PushClient;
import mfs.mw.notify.config.NotifyProperties;
import mfs.mw.notify.dto.NotifyStatus;
import mfs.mw.notify.dto.PushMessage;
import mfs.mw.notify.dto.PushResult;
import mfs.mw.notify.entity.MessageRecord;
import mfs.mw.notify.exception.ErrorCodes;
import mfs.mw.notify.exception.PushRefusedException;
import mfs.mw.notify.exception.PushSendException;
import mfs.mw.notify.repository.MessageRepository;
import mfs.mw.notify.util.MaskingPolicy;
import mfs.mw.notify.util.SensitiveType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends queued PUSH messages (ticket 110, FRS EXT-04, NOT-06, FL-14) on their own scheduled job, apart from
 * SMS and email ({@link DispatchService} never takes a PUSH row), so a slow or failing push server never
 * holds back a transactional SMS. Each message in its own transaction holding its row ({@code FOR UPDATE
 * SKIP LOCKED}), priorities in order, {@code mw.notify.dispatch-batch} per cycle; PROMO is held in the quiet
 * hours as for SMS.
 *
 * <p>Before sending, the token must still be in {@code notify.push_device} (a re-bind revokes the old
 * device's token): else FAILED {@code MW-VAL-103}, nothing sent. The push server's acknowledgement:
 * DELIVERED (final), or only accepted: SENT (final). A 404 (token unknown there) drops the token and fails
 * {@code MW-VAL-103}; another 4xx fails {@code MW-SYS-101}. A transient failure ({@link PushClient} already
 * retried it with backoff) schedules the next attempt after {@code mw.notify.retry-backoff} (2 s, 10 s, 30 s;
 * FAILED final=false {@code MW-SYS-104}), then FAILED {@code MW-SYS-102}.
 */
@Service
public class PushDispatchService {

  private static final Logger log = LoggerFactory.getLogger(PushDispatchService.class);

  private final MessageRepository messages;
  private final PushClient push;
  private final PushDeviceService devices;
  private final StatusEvents statuses;
  private final NotifyProperties props;
  private final TransactionTemplate tx;
  private final Clock clock;

  public PushDispatchService(MessageRepository messages, PushClient push, PushDeviceService devices,
      StatusEvents statuses, NotifyProperties props, TransactionTemplate tx, Clock clock) {
    this.messages = messages;
    this.push = push;
    this.devices = devices;
    this.statuses = statuses;
    this.props = props;
    this.tx = tx;
    this.clock = clock;
  }

  /** Sends the PUSH messages that are due; returns the number handled. */
  public int dispatchDue() {
    Instant now = clock.instant();
    int handled = 0;
    for (MessageRecord.Key key : messages.pushDueKeys(now, props.quietHours().contains(now), props.dispatchBatch())) {
      if (Boolean.TRUE.equals(tx.execute(s -> sendOne(key)))) {
        handled++;
      }
    }
    return handled;
  }

  private boolean sendOne(MessageRecord.Key key) {
    Instant now = clock.instant();
    MessageRecord m = messages.lockDue(key, now).orElse(null);
    if (m == null) {
      return false;
    }
    int attempt = m.attempts() + 1;
    if (!m.expiresAt().isAfter(now)) {
      messages.update(m.withStatus("EXPIRED", m.attempts(), null, null), now);
      statuses.publish(m, NotifyStatus.EXPIRED, true, Math.max(1, m.attempts()), null, null, now);
      return true;
    }
    Optional<String> platform = m.recipientPushToken() == null ? Optional.empty()
        : devices.platformOf(m.recipientPushToken());
    if (platform.isEmpty()) {
      fail(m, attempt, ErrorCodes.NOTIFY_PUSH_TOKEN_GONE, null, now, "push token no longer registered; not sent");
      return true;
    }
    PushResult result;
    try {
      result = push.send(new PushMessage(m.messageId(), m.recipientPushToken(), platform.get(), m.subject(), m.body(),
          m.txnId()));
    } catch (PushRefusedException e) {
      if (e.unregistered()) {
        devices.unregistered(m.recipientPushToken());
      }
      fail(m, attempt, e.unregistered() ? ErrorCodes.NOTIFY_PUSH_TOKEN_GONE : ErrorCodes.NOTIFY_SMSC_REJECTED,
          e.httpStatus(), now, "push refused by the push server");
      return true;
    } catch (PushSendException e) {
      retryOrFail(m, attempt, e, now);
      return true;
    }
    NotifyStatus status = result.delivered() ? NotifyStatus.DELIVERED : NotifyStatus.SENT;
    messages.update(m.withStatus(status.name(), attempt, null, null), now);
    messages.attempt(m.key(), attempt, null, "SENT", null, null, now);
    statuses.publish(m, status, true, attempt, result.providerMessageId(), null, now);
    log.atInfo()
        .addKeyValue("event", "notify.push.sent")
        .addKeyValue("messageId", m.messageId())
        .addKeyValue("txnId", m.txnId())
        .addKeyValue("attempt", attempt)
        .addKeyValue("status", status)
        .addKeyValue("token", MaskingPolicy.mask(SensitiveType.PUSH_TOKEN, m.recipientPushToken()))
        .log("push sent");
    return true;
  }

  private void fail(MessageRecord m, int attempt, String code, Integer httpStatus, Instant now, String why) {
    messages.attempt(m.key(), attempt, null, "REJECTED", code, httpStatus, now);
    messages.update(m.withStatus("FAILED", attempt, null, code), now);
    statuses.publish(m, NotifyStatus.FAILED, true, attempt, null, code, now);
    log.atWarn()
        .addKeyValue("event", "notify.push.failed")
        .addKeyValue("messageId", m.messageId())
        .addKeyValue("txnId", m.txnId())
        .addKeyValue("errorCode", code)
        .addKeyValue("token", MaskingPolicy.mask(SensitiveType.PUSH_TOKEN, m.recipientPushToken()))
        .log(why);
  }

  private void retryOrFail(MessageRecord m, int attempt, PushSendException e, Instant now) {
    boolean retry = attempt <= props.retries();
    String code = retry ? ErrorCodes.NOTIFY_SMSC_TRANSIENT : ErrorCodes.NOTIFY_RETRIES_EXHAUSTED;
    Integer status = e.httpStatus() > 0 ? e.httpStatus() : null;
    messages.attempt(m.key(), attempt, null, "TRANSIENT_ERROR", code, status, now);
    if (retry) {
      messages.update(m.withStatus("RETRY", attempt, now.plus(props.retryBackoff().get(attempt - 1)), code), now);
      statuses.publish(m, NotifyStatus.FAILED, false, attempt, null, code, now);
    } else {
      messages.update(m.withStatus("FAILED", attempt, null, code), now);
      statuses.publish(m, NotifyStatus.FAILED, true, attempt, null, code, now);
    }
    log.atWarn()
        .addKeyValue("event", retry ? "notify.push.retry" : "notify.push.failed")
        .addKeyValue("messageId", m.messageId())
        .addKeyValue("txnId", m.txnId())
        .addKeyValue("attempt", attempt)
        .addKeyValue("httpStatus", status)
        .addKeyValue("errorCode", code)
        .log(retry ? "push failed, retry scheduled" : "push failed");
  }
}
