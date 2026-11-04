package mfs.mw.notify.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import mfs.mw.notify.client.SmsSender;
import mfs.mw.notify.config.NotifyProperties;
import mfs.mw.notify.dto.NotifyStatus;
import mfs.mw.notify.dto.Priority;
import mfs.mw.notify.dto.SmsPart;
import mfs.mw.notify.dto.SubmitResult;
import mfs.mw.notify.entity.MessageRecord;
import mfs.mw.notify.entity.SegmentRecord;
import mfs.mw.notify.exception.ErrorCodes;
import mfs.mw.notify.exception.SmsSendException;
import mfs.mw.notify.repository.MessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends queued SMS (spec 10.1, 10.2, 10.4). Each cycle serves the priorities in order, OTP first,
 * up to {@code mw.notify.dispatch-batch} messages each, so a PROMO backlog never holds back an OTP.
 * Each message is sent in its own transaction holding its row ({@code FOR UPDATE SKIP LOCKED}):
 * an expired message is recorded EXPIRED and not sent; otherwise every part not yet accepted goes
 * to the SMSC. A transient error schedules the next attempt after 2 s, 10 s, 30 s (each attempt
 * takes the next bind of the pool), then the message fails; a permanent refusal fails it at once.
 * Runs on the scheduler thread, never on a Kafka listener thread.
 */
@Service
public class DispatchService {

  private static final Logger log = LoggerFactory.getLogger(DispatchService.class);

  private final MessageRepository messages;
  private final SmsSender sender;
  private final StatusEvents statuses;
  private final ReceiptService receipts;
  private final NotifyProperties props;
  private final TransactionTemplate tx;
  private final Clock clock;

  public DispatchService(MessageRepository messages, SmsSender sender, StatusEvents statuses,
      ReceiptService receipts, NotifyProperties props, TransactionTemplate tx, Clock clock) {
    this.messages = messages;
    this.sender = sender;
    this.statuses = statuses;
    this.receipts = receipts;
    this.props = props;
    this.tx = tx;
    this.clock = clock;
  }

  /** The scheduled cycle: keep the binds up, apply buffered DLRs, send what is due. */
  public int cycle() {
    sender.maintain();
    receipts.applyBuffered();
    return dispatchDue();
  }

  /** Sends what is due; returns the number of messages handled. */
  public int dispatchDue() {
    int handled = 0;
    for (Priority p : Priority.values()) {
      List<String> due = messages.dueIds(p.rank(), clock.instant(), props.dispatchBatch());
      for (String id : due) {
        Boolean done = tx.execute(s -> sendOne(id));
        if (Boolean.TRUE.equals(done)) {
          handled++;
        }
      }
    }
    return handled;
  }

  private boolean sendOne(String messageId) {
    Instant now = clock.instant();
    MessageRecord m = messages.lockDue(messageId, now).orElse(null);
    if (m == null) {
      return false;
    }
    int attempt = m.attempts() + 1;
    if (!m.expiresAt().isAfter(now)) {
      messages.update(m.withStatus("EXPIRED", m.attempts(), null, null), now);
      statuses.publish(m, NotifyStatus.EXPIRED, true, Math.max(1, m.attempts()), null, null, now);
      return true;
    }
    Priority priority = Priority.valueOf(m.priority());
    EncodedSms sms = SmsEncoder.encode(m.body(), m.concatRef() == null ? 0 : m.concatRef());
    List<SegmentRecord> segments = messages.segments(m.messageId());
    String firstProviderId = null;
    String bind = null;
    try {
      for (SegmentRecord seg : segments) {
        if (seg.providerMessageId() != null) {
          firstProviderId = firstProviderId == null ? seg.providerMessageId() : firstProviderId;
          continue;
        }
        SmsPart part = new SmsPart(m.recipientMsisdn(), m.senderId(), sms.dataCoding(), sms.udhi(),
            sms.segments().get(seg.seq() - 1));
        SubmitResult result = sender.submit(priority, part);
        bind = result.bind();
        messages.segmentSent(m.messageId(), seg.seq(), result.providerMessageId());
        firstProviderId = firstProviderId == null ? result.providerMessageId() : firstProviderId;
      }
    } catch (SmsSendException e) {
      return failed(m, attempt, e, now);
    }
    messages.update(m.withStatus("SENT", attempt, null, null), now);
    messages.attempt(m.messageId(), attempt, bind, "SENT", null, null, now);
    statuses.publish(m, NotifyStatus.SENT, false, attempt, firstProviderId, null, now);
    log.atInfo()
        .addKeyValue("event", "notify.sms.sent")
        .addKeyValue("messageId", m.messageId())
        .addKeyValue("txnId", m.txnId())
        .addKeyValue("priority", priority)
        .addKeyValue("attempt", attempt)
        .addKeyValue("segments", segments.size())
        .log("SMS submitted");
    // A DLR may have arrived before this transaction made the segments visible.
    receipts.applyBufferedFor(m.messageId());
    receipts.settle(m.messageId());
    return true;
  }

  private boolean failed(MessageRecord m, int attempt, SmsSendException e, Instant now) {
    boolean retry = e.isTransient() && attempt <= props.retries();
    String code;
    if (!e.isTransient()) {
      code = ErrorCodes.NOTIFY_SMSC_REJECTED;
    } else {
      code = retry ? ErrorCodes.NOTIFY_SMSC_TRANSIENT : ErrorCodes.NOTIFY_RETRIES_EXHAUSTED;
    }
    messages.attempt(m.messageId(), attempt, e.bind(), e.isTransient() ? "TRANSIENT_ERROR" : "REJECTED", code,
        e.smppStatus(), now);
    if (retry) {
      Instant next = now.plus(props.retryBackoff().get(attempt - 1));
      messages.update(m.withStatus("RETRY", attempt, next, code), now);
      statuses.publish(m, NotifyStatus.FAILED, false, attempt, null, code, now);
    } else {
      messages.update(m.withStatus("FAILED", attempt, null, code), now);
      statuses.publish(m, NotifyStatus.FAILED, true, attempt, null, code, now);
    }
    log.atWarn()
        .addKeyValue("event", retry ? "notify.sms.retry" : "notify.sms.failed")
        .addKeyValue("messageId", m.messageId())
        .addKeyValue("txnId", m.txnId())
        .addKeyValue("attempt", attempt)
        .addKeyValue("bind", e.bind())
        .addKeyValue("smppStatus", e.smppStatus())
        .addKeyValue("errorCode", code)
        .log(retry ? "SMS submit failed, retry scheduled" : "SMS submit failed");
    return true;
  }
}
