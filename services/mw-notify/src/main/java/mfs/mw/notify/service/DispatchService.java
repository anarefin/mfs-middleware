package mfs.mw.notify.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import mfs.mw.notify.client.EmailSender;
import mfs.mw.notify.client.SmsSender;
import mfs.mw.notify.config.NotifyProperties;
import mfs.mw.notify.dto.Channel;
import mfs.mw.notify.dto.EmailMessage;
import mfs.mw.notify.dto.NotifyStatus;
import mfs.mw.notify.dto.Priority;
import mfs.mw.notify.dto.SmsPart;
import mfs.mw.notify.dto.SubmitResult;
import mfs.mw.notify.entity.MessageRecord;
import mfs.mw.notify.entity.SegmentRecord;
import mfs.mw.notify.exception.EmailSendException;
import mfs.mw.notify.exception.ErrorCodes;
import mfs.mw.notify.exception.SmsSendException;
import mfs.mw.notify.repository.MessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends queued SMS and email (spec 10.1, 10.2, 10.4). Each cycle serves the priorities in order, OTP first,
 * up to {@code mw.notify.dispatch-batch} messages each, so a PROMO backlog never holds back an OTP.
 * Each message is sent in its own transaction holding its row ({@code FOR UPDATE SKIP LOCKED}):
 * an expired message is recorded EXPIRED and not sent; otherwise every part not yet accepted goes
 * to the SMSC. A transient error schedules the next attempt after 2 s, 10 s, 30 s (each attempt
 * takes the next bind of the pool), then the message fails; a permanent refusal fails it at once.
 * An email goes to the SMTP server in one piece with the same retries; it is final once SENT (no
 * delivery receipts for email). Runs on the scheduler thread, never on a Kafka listener thread.
 */
@Service
public class DispatchService {

  private static final Logger log = LoggerFactory.getLogger(DispatchService.class);

  private final MessageRepository messages;
  private final SmsSender sender;
  private final EmailSender email;
  private final StatusEvents statuses;
  private final ReceiptService receipts;
  private final NotifyProperties props;
  private final TransactionTemplate tx;
  private final Clock clock;

  public DispatchService(MessageRepository messages, SmsSender sender, EmailSender email, StatusEvents statuses,
      ReceiptService receipts, NotifyProperties props, TransactionTemplate tx, Clock clock) {
    this.messages = messages;
    this.sender = sender;
    this.email = email;
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
      List<MessageRecord.Key> due = messages.dueKeys(p.rank(), clock.instant(), props.dispatchBatch());
      for (MessageRecord.Key key : due) {
        Boolean done = tx.execute(s -> sendOne(key));
        if (Boolean.TRUE.equals(done)) {
          handled++;
        }
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
    if (Channel.EMAIL.name().equals(m.channel())) {
      return sendEmail(m, attempt, now);
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
    messages.attempt(m.key(), attempt, bind, "SENT", null, null, now);
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

  private boolean sendEmail(MessageRecord m, int attempt, Instant now) {
    String messageId;
    try {
      messageId = email.send(new EmailMessage(m.recipientEmail(), m.subject(), m.body(), m.htmlBody()));
    } catch (EmailSendException e) {
      boolean retry = schedule(m, attempt, e.isTransient(), null, e.smtpCode() > 0 ? e.smtpCode() : null, now);
      log.atWarn()
          .addKeyValue("event", retry ? "notify.email.retry" : "notify.email.failed")
          .addKeyValue("messageId", m.messageId())
          .addKeyValue("txnId", m.txnId())
          .addKeyValue("attempt", attempt)
          .addKeyValue("smtpCode", e.smtpCode())
          .log(retry ? "email send failed, retry scheduled" : "email send failed");
      return true;
    }
    messages.update(m.withStatus("SENT", attempt, null, null), now);
    messages.attempt(m.key(), attempt, null, "SENT", null, null, now);
    statuses.publish(m, NotifyStatus.SENT, true, attempt, messageId, null, now);
    log.atInfo()
        .addKeyValue("event", "notify.email.sent")
        .addKeyValue("messageId", m.messageId())
        .addKeyValue("txnId", m.txnId())
        .addKeyValue("attempt", attempt)
        .log("email sent");
    return true;
  }

  private boolean failed(MessageRecord m, int attempt, SmsSendException e, Instant now) {
    boolean retry = schedule(m, attempt, e.isTransient(), e.bind(), e.smppStatus(), now);
    log.atWarn()
        .addKeyValue("event", retry ? "notify.sms.retry" : "notify.sms.failed")
        .addKeyValue("messageId", m.messageId())
        .addKeyValue("txnId", m.txnId())
        .addKeyValue("attempt", attempt)
        .addKeyValue("bind", e.bind())
        .addKeyValue("smppStatus", e.smppStatus())
        .addKeyValue("errorCode", retryCode(e.isTransient(), retry))
        .log(retry ? "SMS submit failed, retry scheduled" : "SMS submit failed");
    return true;
  }

  /**
   * Records a failed attempt and either schedules the next one (transient, retries left: RETRY,
   * FAILED final=false) or fails the message (FAILED final=true); true when a retry is scheduled.
   */
  private boolean schedule(MessageRecord m, int attempt, boolean transientError, String bind, Integer providerStatus,
      Instant now) {
    boolean retry = transientError && attempt <= props.retries();
    String code = retryCode(transientError, retry);
    messages.attempt(m.key(), attempt, bind, transientError ? "TRANSIENT_ERROR" : "REJECTED", code,
        providerStatus, now);
    if (retry) {
      Instant next = now.plus(props.retryBackoff().get(attempt - 1));
      messages.update(m.withStatus("RETRY", attempt, next, code), now);
      statuses.publish(m, NotifyStatus.FAILED, false, attempt, null, code, now);
    } else {
      messages.update(m.withStatus("FAILED", attempt, null, code), now);
      statuses.publish(m, NotifyStatus.FAILED, true, attempt, null, code, now);
    }
    return retry;
  }

  private static String retryCode(boolean transientError, boolean retry) {
    if (!transientError) {
      return ErrorCodes.NOTIFY_SMSC_REJECTED;
    }
    return retry ? ErrorCodes.NOTIFY_SMSC_TRANSIENT : ErrorCodes.NOTIFY_RETRIES_EXHAUSTED;
  }
}
