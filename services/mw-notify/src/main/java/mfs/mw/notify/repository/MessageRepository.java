package mfs.mw.notify.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import mfs.mw.notify.entity.MessageRecord;
import mfs.mw.notify.entity.SegmentRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** {@code notify.message}, {@code segment}, {@code attempt} and {@code receipt} (spec 10, 16.2). */
@Repository
public class MessageRepository {

  private static final String COLUMNS =
      "message_id, txn_id, correlation_id, channel, recipient_msisdn, template_code, template_version, locale,"
          + " priority, sender_id, body, concat_ref, segment_count, status, attempts, next_attempt_at, expires_at,"
          + " error_code, recipient_email, subject, html_body";

  private final JdbcTemplate jdbc;

  public MessageRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Inserts the message unless its (id, channel) exists (10.1 idempotency); true when inserted. */
  public boolean insertIfAbsent(MessageRecord m, int priorityRank) {
    return jdbc.update(
            "insert into message (" + COLUMNS + ", priority_rank) values"
                + " (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                + " on conflict (message_id, channel) do nothing",
            m.messageId(), m.txnId(), m.correlationId(), m.channel(), m.recipientMsisdn(), m.templateCode(),
            m.templateVersion(), m.locale(), m.priority(), m.senderId(), m.body(), m.concatRef(),
            m.segmentCount(), m.status(), m.attempts(), ts(m.nextAttemptAt()), ts(m.expiresAt()),
            m.errorCode(), m.recipientEmail(), m.subject(), m.htmlBody(), priorityRank)
        == 1;
  }

  /** Keys due at {@code now} for one priority, oldest first (no lock; see {@link #lockDue}). */
  public List<MessageRecord.Key> dueKeys(int priorityRank, Instant now, int limit) {
    return jdbc.query(
        "select message_id, channel from message where priority_rank = ? and status in ('QUEUED', 'RETRY')"
            + " and next_attempt_at <= ? order by next_attempt_at, created_at limit ?",
        (rs, i) -> new MessageRecord.Key(rs.getString(1), rs.getString(2)), priorityRank, ts(now), limit);
  }

  /**
   * Keys due at {@code now} for one priority whose expiry has passed, oldest first: during PROMO
   * quiet hours only these are taken, to record them EXPIRED.
   */
  public List<MessageRecord.Key> expiredDueKeys(int priorityRank, Instant now, int limit) {
    return jdbc.query(
        "select message_id, channel from message where priority_rank = ? and status in ('QUEUED', 'RETRY')"
            + " and next_attempt_at <= ? and expires_at <= ? order by next_attempt_at, created_at limit ?",
        (rs, i) -> new MessageRecord.Key(rs.getString(1), rs.getString(2)), priorityRank, ts(now), ts(now), limit);
  }

  /** Locks the message if it is still due, skipping one another dispatcher holds. */
  public Optional<MessageRecord> lockDue(MessageRecord.Key key, Instant now) {
    return jdbc.query(
            "select " + COLUMNS + " from message where message_id = ? and channel = ?"
                + " and status in ('QUEUED', 'RETRY') and next_attempt_at <= ? for update skip locked",
            MessageRepository::message, key.messageId(), key.channel(), ts(now))
        .stream()
        .findFirst();
  }

  /** Locks the SMS message (waits for a dispatcher holding it); DLRs only concern SMS. */
  public Optional<MessageRecord> lockSms(String messageId) {
    return jdbc.query("select " + COLUMNS + " from message where message_id = ? and channel = 'SMS' for update",
            MessageRepository::message, messageId)
        .stream()
        .findFirst();
  }

  public void update(MessageRecord m, Instant now) {
    jdbc.update(
        "update message set status = ?, attempts = ?, next_attempt_at = ?, error_code = ?, updated_at = ?"
            + " where message_id = ? and channel = ?",
        m.status(), m.attempts(), ts(m.nextAttemptAt()), m.errorCode(), ts(now), m.messageId(), m.channel());
  }

  public void insertSegments(String messageId, int count) {
    for (int seq = 1; seq <= count; seq++) {
      jdbc.update("insert into segment (message_id, seq) values (?, ?)", messageId, seq);
    }
  }

  public List<SegmentRecord> segments(String messageId) {
    return jdbc.query(
        "select message_id, seq, provider_message_id, dlr_status from segment where message_id = ? order by seq",
        (rs, i) -> new SegmentRecord(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getString(4)),
        messageId);
  }

  public void segmentSent(String messageId, int seq, String providerMessageId) {
    jdbc.update("update segment set provider_message_id = ? where message_id = ? and seq = ?",
        providerMessageId, messageId, seq);
  }

  public Optional<SegmentRecord> segmentByProviderId(String providerMessageId) {
    return jdbc.query(
            "select message_id, seq, provider_message_id, dlr_status from segment where provider_message_id = ?",
            (rs, i) -> new SegmentRecord(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getString(4)),
            providerMessageId)
        .stream()
        .findFirst();
  }

  /** Records the DLR on the segment unless it already has one; true when recorded. */
  public boolean segmentReceipt(String providerMessageId, String dlrStatus) {
    return jdbc.update(
            "update segment set dlr_status = ? where provider_message_id = ? and dlr_status is null",
            dlrStatus, providerMessageId)
        == 1;
  }

  /** Records an attempt; {@code bind} is the SMPP bind, or null for email. */
  public void attempt(MessageRecord.Key key, int attemptNo, String bind, String outcome, String errorCode,
      Integer smppStatus, Instant at) {
    jdbc.update(
        "insert into attempt (message_id, channel, attempt_no, bind_name, outcome, error_code, smpp_status, at)"
            + " values (?, ?, ?, ?, ?, ?, ?, ?) on conflict do nothing",
        key.messageId(), key.channel(), attemptNo, bind, outcome, errorCode, smppStatus, ts(at));
  }

  /** Buffers a DLR whose segment is not known yet. */
  public void bufferReceipt(String providerMessageId, String dlrStatus, Instant at) {
    jdbc.update(
        "insert into receipt (provider_message_id, dlr_status, received_at) values (?, ?, ?)"
            + " on conflict do nothing",
        providerMessageId, dlrStatus, ts(at));
  }

  /** Buffered DLRs whose segment now exists: {@code [providerMessageId, dlrStatus]}. */
  public List<String[]> matchedReceipts(int limit) {
    return jdbc.query(
        "select r.provider_message_id, r.dlr_status from receipt r join segment s"
            + " on s.provider_message_id = r.provider_message_id order by r.received_at limit ?",
        (rs, i) -> new String[] {rs.getString(1), rs.getString(2)}, limit);
  }

  public void deleteReceipt(String providerMessageId) {
    jdbc.update("delete from receipt where provider_message_id = ?", providerMessageId);
  }

  /** Drops buffered DLRs that never matched a segment. */
  public int purgeReceipts(Instant olderThan) {
    return jdbc.update("delete from receipt where received_at < ?", ts(olderThan));
  }

  private static MessageRecord message(ResultSet rs, int i) throws SQLException {
    return new MessageRecord(
        rs.getString("message_id"), rs.getString("txn_id"), rs.getString("correlation_id"),
        rs.getString("channel"), rs.getString("recipient_msisdn"), rs.getString("template_code"),
        (Integer) rs.getObject("template_version"), rs.getString("locale"), rs.getString("priority"),
        rs.getString("sender_id"), rs.getString("body"), (Integer) rs.getObject("concat_ref"),
        (Integer) rs.getObject("segment_count"), rs.getString("status"), rs.getInt("attempts"),
        instant(rs.getTimestamp("next_attempt_at")), instant(rs.getTimestamp("expires_at")),
        rs.getString("error_code"), rs.getString("recipient_email"), rs.getString("subject"),
        rs.getString("html_body"));
  }

  private static Timestamp ts(Instant i) {
    return i == null ? null : Timestamp.from(i);
  }

  private static Instant instant(Timestamp t) {
    return t == null ? null : t.toInstant();
  }
}
