package mfs.mw.notify.repository;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The customer inbox (ticket 107a): sent TXN, INFO and PROMO for one MSISDN. OTP rows are never selected. */
@Repository
public class InboxRepository {

  /** One stored row, before masking. */
  public record Row(
      String messageId,
      String channel,
      String txnId,
      String msisdn,
      String priority,
      String body,
      Instant readAt,
      Instant createdAt) {}

  private static final String VISIBLE =
      " priority in ('TXN', 'INFO', 'PROMO') and status in ('SENT', 'DELIVERED')";

  private final JdbcTemplate jdbc;
  private final Clock clock;

  public InboxRepository(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  public List<Row> page(String msisdn, int limit, int offset) {
    return jdbc.query(
        """
        select message_id, channel, txn_id, recipient_msisdn, priority, body, read_at, created_at
        from message
        where recipient_msisdn = ? and
        """
            + VISIBLE
            + " order by created_at desc, message_id, channel limit ? offset ?",
        (rs, i) -> new Row(rs.getString("message_id"), rs.getString("channel"), rs.getString("txn_id"),
            rs.getString("recipient_msisdn"), rs.getString("priority"), rs.getString("body"),
            ts(rs.getTimestamp("read_at")), rs.getTimestamp("created_at").toInstant()),
        msisdn, limit, offset);
  }

  /** Marks every visible row of this message for this MSISDN. Returns how many rows that was. */
  public int markRead(String msisdn, String messageId) {
    return jdbc.update(
        """
        update message set read_at = coalesce(read_at, ?)
        where message_id = ? and recipient_msisdn = ? and
        """
            + VISIBLE,
        Timestamp.from(clock.instant()), messageId, msisdn);
  }

  private static Instant ts(Timestamp t) {
    return t == null ? null : t.toInstant();
  }
}
