package mfs.mw.notify.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import mfs.mw.notify.dto.NotificationLogQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The notification log query (ticket 111, NOT-08). */
@Repository
public class NotificationLogRepository {

  /** One stored row, before masking. */
  public record Row(
      String messageId,
      String txnId,
      String channel,
      String msisdn,
      String priority,
      String status,
      String templateCode,
      String body,
      Instant createdAt) {}

  private final JdbcTemplate jdbc;

  public NotificationLogRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<Row> search(NotificationLogQuery q) {
    StringBuilder sql = new StringBuilder(
        "select message_id, txn_id, channel, recipient_msisdn, priority, status, template_code, body, created_at"
            + " from message where true");
    List<Object> args = new ArrayList<>();
    if (q.msisdn() != null) {
      sql.append(" and recipient_msisdn = ?");
      args.add(q.msisdn());
    }
    if (q.txnId() != null) {
      sql.append(" and txn_id = ?");
      args.add(q.txnId());
    }
    if (q.channel() != null) {
      sql.append(" and channel = ?");
      args.add(q.channel());
    }
    if (q.status() != null) {
      sql.append(" and status = ?");
      args.add(q.status());
    }
    if (q.from() != null) {
      sql.append(" and created_at >= ?");
      args.add(Timestamp.from(q.from()));
    }
    if (q.to() != null) {
      sql.append(" and created_at < ?");
      args.add(Timestamp.from(q.to()));
    }
    sql.append(" order by created_at desc, message_id limit ? offset ?");
    args.add(q.limit());
    args.add(q.offset());
    return jdbc.query(sql.toString(), (rs, i) -> new Row(rs.getString("message_id"), rs.getString("txn_id"),
        rs.getString("channel"), rs.getString("recipient_msisdn"), rs.getString("priority"), rs.getString("status"),
        rs.getString("template_code"), rs.getString("body"), rs.getTimestamp("created_at").toInstant()),
        args.toArray());
  }
}
