package mfs.mw.audit.dw.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import mfs.mw.audit.dw.entity.AuditRow;
import mfs.mw.audit.dw.entity.ChainHead;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Recomputes one month's hash chain from {@code audit.event} and checks it against
 * {@code audit.chain_head} (spec 14.3). Deliberately not a Spring bean: the service connects as
 * {@code audit_writer}, which may only INSERT into {@code audit.event} and cannot read it, so a
 * verifier runs with a reading role (owner, {@code audit_dw_ro} or an auditor's break-glass
 * account) and is given that role's {@link JdbcTemplate}.
 */
public class AuditChainVerifier {

  private final JdbcTemplate jdbc;

  public AuditChainVerifier(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
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
