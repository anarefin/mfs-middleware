package mfs.mw.audit.dw.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import mfs.mw.audit.dw.dto.AuditEntry;
import mfs.mw.audit.dw.dto.AuditEvent;
import mfs.mw.audit.dw.dto.AuditSearchCriteria;
import mfs.mw.audit.dw.dto.TimelineHop;
import mfs.mw.audit.dw.util.Hops;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads {@code audit.event}, {@code audit.payload} and the search projection {@code
 * audit.txn_index} for the search, timeline and export APIs (tickets 35, 130). The service's own
 * role is INSERT-only, so this is given the read-only {@code audit_reader}'s template ({@code
 * config/AuditSearchConfig}); it can never write.
 */
public class AuditSearchRepository {

  private static final String COLUMNS =
      "e.audit_id, e.month, e.seq, e.recorded_at, e.at, e.txn_id, e.correlation_id, e.actor_type, e.actor_id,"
          + " e.action, e.resource, e.outcome, e.service, e.source_ip, e.payload_ref, e.before::text before,"
          + " e.after::text after, e.hop, e.direction, e.duration_ms";

  private final JdbcTemplate reader;
  private final JsonMapper json;

  public AuditSearchRepository(JdbcTemplate reader, JsonMapper json) {
    this.reader = reader;
    this.json = json;
  }

  /** Matching events in the order they happened ({@code at}, then chain order). */
  public List<AuditEntry> search(AuditSearchCriteria c) {
    StringBuilder sql = new StringBuilder("select ").append(COLUMNS).append(" from event e");
    List<Object> args = new ArrayList<>();
    if (c.filtersTransactions()) {
      sql.append(" join txn_index t on t.txn_id = e.txn_id");
    }
    sql.append(" where true");
    add(sql, args, " and e.txn_id = ?", c.txnId());
    add(sql, args, " and e.actor_type = ?", c.actorType());
    add(sql, args, " and e.actor_id = ?", c.actorId());
    add(sql, args, " and e.action = ?", c.action());
    add(sql, args, " and e.resource = ?", c.resource());
    add(sql, args, " and e.outcome = ?", c.outcome());
    add(sql, args, " and e.at >= ?", c.from() == null ? null : Timestamp.from(c.from()));
    add(sql, args, " and e.at < ?", c.to() == null ? null : Timestamp.from(c.to()));
    add(sql, args, " and t.partner_id = ?", c.partner());
    add(sql, args, " and t.channel = ?", c.channel());
    add(sql, args, " and t.status = ?", c.status());
    add(sql, args, " and t.amount_minor >= ?", c.amountMin());
    add(sql, args, " and t.amount_minor <= ?", c.amountMax());
    add(sql, args, " and t.msisdn_tokens @> array[?]::text[]", c.msisdnToken());
    sql.append(" order by e.at, e.recorded_at, e.month, e.seq limit ? offset ?");
    args.add(c.limit());
    args.add(c.offset());
    return reader.query(sql.toString(), (rs, i) -> entry(rs), args.toArray());
  }

  /** Every event of one transaction in the order it happened, as timeline hops with their payloads. */
  public List<TimelineHop> timeline(UUID txnId) {
    List<AuditEntry> events =
        reader.query(
            "select " + COLUMNS + " from event e where e.txn_id = ? order by e.at, e.recorded_at, e.month, e.seq",
            (rs, i) -> entry(rs),
            txnId);
    if (events.isEmpty()) {
      return List.of();
    }
    java.util.Map<String, List<TimelineHop.Payload>> payloads = new java.util.HashMap<>();
    reader.query(
        """
        select p.audit_id, p.direction, p.content_type, p.body, p.payload_ref, p.size, p.sha256
          from payload p join event e on e.audit_id = p.audit_id and e.month = p.month
         where e.txn_id = ? order by p.audit_id, p.ordinal
        """,
        rs -> {
          byte[] sha = rs.getBytes("sha256");
          payloads
              .computeIfAbsent(rs.getObject("audit_id", UUID.class).toString(), k -> new ArrayList<>())
              .add(
                  new TimelineHop.Payload(
                      rs.getString("direction"),
                      rs.getString("content_type"),
                      rs.getString("body"),
                      rs.getString("payload_ref"),
                      (Integer) rs.getObject("size"),
                      sha == null ? null : java.util.HexFormat.of().formatHex(sha)));
        },
        txnId);
    List<TimelineHop> hops = new ArrayList<>();
    for (AuditEntry e : events) {
      hops.add(TimelineHop.of(e, payloads.getOrDefault(e.auditId(), List.of())));
    }
    return hops;
  }

  private AuditEntry entry(ResultSet rs) throws SQLException {
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
        after == null ? null : json.readTree(after),
        Hops.effective(rs.getString("hop"), rs.getString("action")),
        rs.getString("direction"),
        (Long) rs.getObject("duration_ms"));
  }

  private static void add(StringBuilder sql, List<Object> args, String clause, Object value) {
    if (value != null) {
      sql.append(clause);
      args.add(value);
    }
  }
}
