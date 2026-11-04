package mfs.mw.audit.dw.repository;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import mfs.mw.audit.dw.dto.AuditEntry;
import mfs.mw.audit.dw.dto.AuditEvent;
import mfs.mw.audit.dw.dto.AuditSearchCriteria;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads {@code audit.event} for the search API (ticket 35). The service's own role is INSERT-only,
 * so this is given the read-only {@code audit_reader}'s template ({@code
 * config/AuditSearchConfig}); it can never write.
 */
public class AuditSearchRepository {

  private final JdbcTemplate reader;
  private final JsonMapper json;

  public AuditSearchRepository(JdbcTemplate reader, JsonMapper json) {
    this.reader = reader;
    this.json = json;
  }

  /** Matching events in the order they happened ({@code at}, then chain order). */
  public List<AuditEntry> search(AuditSearchCriteria c) {
    StringBuilder sql =
        new StringBuilder(
            "select audit_id, month, seq, recorded_at, at, txn_id, correlation_id, actor_type, actor_id,"
                + " action, resource, outcome, service, source_ip, payload_ref, before::text, after::text"
                + " from event where true");
    List<Object> args = new ArrayList<>();
    add(sql, args, " and txn_id = ?", c.txnId());
    add(sql, args, " and actor_type = ?", c.actorType());
    add(sql, args, " and actor_id = ?", c.actorId());
    add(sql, args, " and action = ?", c.action());
    add(sql, args, " and resource = ?", c.resource());
    add(sql, args, " and outcome = ?", c.outcome());
    add(sql, args, " and at >= ?", c.from() == null ? null : Timestamp.from(c.from()));
    add(sql, args, " and at < ?", c.to() == null ? null : Timestamp.from(c.to()));
    sql.append(" order by at, recorded_at, month, seq limit ? offset ?");
    args.add(c.limit());
    args.add(c.offset());
    return reader.query(
        sql.toString(),
        (rs, i) -> {
          String before = rs.getString("before");
          String after = rs.getString("after");
          UUID txn = rs.getObject("txn_id", UUID.class);
          return new AuditEntry(
              rs.getObject("audit_id", UUID.class).toString(),
              rs.getString("month"),
              rs.getLong("seq"),
              rs.getTimestamp("recorded_at").toInstant(),
              rs.getTimestamp("at").toInstant(),
              txn == null ? null : txn.toString(),
              rs.getString("correlation_id"),
              new AuditEvent.Actor(rs.getString("actor_type"), rs.getString("actor_id")),
              rs.getString("action"),
              rs.getString("resource"),
              rs.getString("outcome"),
              rs.getString("service"),
              rs.getString("source_ip"),
              rs.getString("payload_ref"),
              before == null ? null : json.readTree(before),
              after == null ? null : json.readTree(after));
        },
        args.toArray());
  }

  private static void add(StringBuilder sql, List<Object> args, String clause, Object value) {
    if (value != null) {
      sql.append(clause);
      args.add(value);
    }
  }
}
