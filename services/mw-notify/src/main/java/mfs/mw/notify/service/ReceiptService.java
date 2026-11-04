package mfs.mw.notify.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import mfs.mw.notify.config.NotifyProperties;
import mfs.mw.notify.dto.DeliveryReport;
import mfs.mw.notify.dto.DlrOutcome;
import mfs.mw.notify.dto.NotifyStatus;
import mfs.mw.notify.entity.MessageRecord;
import mfs.mw.notify.entity.SegmentRecord;
import mfs.mw.notify.exception.ErrorCodes;
import mfs.mw.notify.repository.MessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies SMSC delivery receipts (spec 10.2): a DLR is matched on the part's SMSC message_id; once
 * every part of a SENT message has a final DLR the message takes the worst outcome (DELIVERED only
 * when all parts are) and the final status is published. A repeated DLR for a part changes
 * nothing. A DLR that arrives before its part is stored (the SMSC can answer faster than the
 * submit commits) is buffered in {@code notify.receipt} and applied once the part exists.
 */
@Service
public class ReceiptService {

  private static final Logger log = LoggerFactory.getLogger(ReceiptService.class);

  private static final int BUFFER_BATCH = 200;

  private final MessageRepository messages;
  private final StatusEvents statuses;
  private final NotifyProperties props;
  private final Clock clock;

  public ReceiptService(MessageRepository messages, StatusEvents statuses, NotifyProperties props, Clock clock) {
    this.messages = messages;
    this.statuses = statuses;
    this.props = props;
    this.clock = clock;
  }

  @Transactional
  public void onReceipt(DeliveryReport report) {
    if (messages.segmentByProviderId(report.providerMessageId()).isEmpty()) {
      messages.bufferReceipt(report.providerMessageId(), report.outcome().name(), clock.instant());
      log.atInfo()
          .addKeyValue("event", "notify.dlr.buffered")
          .addKeyValue("providerMessageId", report.providerMessageId())
          .log("DLR for an unknown part buffered");
      return;
    }
    apply(report.providerMessageId(), report.outcome());
  }

  /** Applies buffered DLRs whose part is now known, and drops those too old to ever match. */
  @Transactional
  public int applyBuffered() {
    List<String[]> matched = messages.matchedReceipts(BUFFER_BATCH);
    for (String[] r : matched) {
      apply(r[0], DlrOutcome.valueOf(r[1]));
      messages.deleteReceipt(r[0]);
    }
    messages.purgeReceipts(clock.instant().minus(props.receiptBufferTtl()));
    return matched.size();
  }

  /** Applies buffered DLRs of one message, in the caller's transaction. */
  @Transactional
  public void applyBufferedFor(String messageId) {
    for (String[] r : messages.matchedReceipts(BUFFER_BATCH)) {
      messages.segmentByProviderId(r[0])
          .filter(s -> s.messageId().equals(messageId))
          .ifPresent(s -> {
            apply(r[0], DlrOutcome.valueOf(r[1]));
            messages.deleteReceipt(r[0]);
          });
    }
  }

  private void apply(String providerMessageId, DlrOutcome outcome) {
    SegmentRecord seg = messages.segmentByProviderId(providerMessageId).orElseThrow();
    MessageRecord m = messages.lock(seg.messageId()).orElseThrow();
    if (!messages.segmentReceipt(providerMessageId, outcome.name())) {
      log.atInfo()
          .addKeyValue("event", "notify.dlr.duplicate")
          .addKeyValue("messageId", m.messageId())
          .addKeyValue("providerMessageId", providerMessageId)
          .log("repeated DLR ignored");
      return;
    }
    settle(m);
  }

  /**
   * Finalises a SENT message whose parts all have a DLR, in the caller's transaction (also called
   * by the dispatcher after a send: a part sent on an earlier attempt may already have its DLR).
   */
  @Transactional
  public void settle(String messageId) {
    messages.lock(messageId).ifPresent(this::settle);
  }

  private void settle(MessageRecord m) {
    if (!"SENT".equals(m.status())) {
      return;
    }
    List<SegmentRecord> segments = messages.segments(m.messageId());
    if (segments.stream().anyMatch(s -> s.dlrStatus() == null)) {
      return;
    }
    DlrOutcome worst = segments.stream()
        .map(s -> DlrOutcome.valueOf(s.dlrStatus()))
        .max(Enum::compareTo)
        .orElseThrow();
    Instant now = clock.instant();
    String error = worst == DlrOutcome.UNDELIVERABLE ? ErrorCodes.NOTIFY_UNDELIVERABLE : null;
    NotifyStatus status = worst.status();
    messages.update(m.withStatus(status.name(), m.attempts(), null, error), now);
    statuses.publish(m, status, true, m.attempts(), segments.getFirst().providerMessageId(), error, now);
  }
}
