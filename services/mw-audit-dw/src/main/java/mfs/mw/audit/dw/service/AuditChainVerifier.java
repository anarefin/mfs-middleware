package mfs.mw.audit.dw.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import mfs.mw.audit.dw.entity.AuditRow;
import mfs.mw.audit.dw.entity.ChainHead;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Recomputes one month's hash chain from {@code audit.event} and checks it against
 * {@code audit.chain_head} (spec 14.3). The service connects as {@code audit_writer}, which may
 * only INSERT into {@code audit.event} and cannot read it, so a verifier is given a reading
 * role's {@link JdbcTemplate}: the read-only {@code audit_reader} for the scheduled check
 * ({@code config/AuditReaderConfig}, ticket 50), or the owner or an auditor's account.
 */
public class AuditChainVerifier {

  private final JdbcTemplate jdbc;

  public AuditChainVerifier(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Every month that has a chain ({@code yyyy-MM}, oldest first). */
  public List<String> months() {
    return jdbc.queryForList("select month from chain_head order by month", String.class);
  }

  /** @param month {@code yyyy-MM} */
  public ChainVerification verify(String month) {
    List<AuditRow> rows =
        jdbc.query(
            """
            select month, seq, audit_id, recorded_at, at, txn_id, correlation_id, actor_type,
                   actor_id, action, resource, outcome, service, source_ip, payload_ref,
                   before::text before, after::text after, prev_hash, hash
              from event where month = ? order by seq
            """,
            (rs, i) ->
                new AuditRow(
                    rs.getString("month"),
                    rs.getLong("seq"),
                    rs.getObject("audit_id", UUID.class),
                    rs.getObject("recorded_at", OffsetDateTime.class).toInstant(),
                    rs.getObject("at", OffsetDateTime.class).toInstant(),
                    rs.getObject("txn_id", UUID.class),
                    rs.getString("correlation_id"),
                    rs.getString("actor_type"),
                    rs.getString("actor_id"),
                    rs.getString("action"),
                    rs.getString("resource"),
                    rs.getString("outcome"),
                    rs.getString("service"),
                    rs.getString("source_ip"),
                    rs.getString("payload_ref"),
                    rs.getString("before"),
                    rs.getString("after"),
                    rs.getBytes("prev_hash"),
                    rs.getBytes("hash")),
            month);
    List<ChainHead> head =
        jdbc.query(
            "select month, seq, hash from chain_head where month = ?",
            (rs, i) -> new ChainHead(rs.getString("month"), rs.getLong("seq"), rs.getBytes("hash")),
            month);
    return HashChain.verify(rows, head.isEmpty() ? null : head.get(0));
  }
}
