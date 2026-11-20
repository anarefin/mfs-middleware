package mfs.mw.adapter.card.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import mfs.mw.adapter.card.partner.route.PartnerAudit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Table {@code partner_payload} (ticket 97): the full masked bodies of the partner calls, the
 * engine's {@link PartnerAudit.PayloadStore}. Written outside any business transaction (the call
 * happened whatever becomes of the caller's).
 */
@Repository
public class PartnerPayloadRepository implements PartnerAudit.PayloadStore {

  private final JdbcTemplate jdbc;

  public PartnerPayloadRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void store(List<PartnerAudit.Payload> payloads) {
    jdbc.batchUpdate(
        "insert into partner_payload (payload_ref, txn_id, partner_id, op, direction, body, latency_ms)"
            + " values (?, ?, ?, ?, ?, ?, ?)",
        payloads,
        payloads.size(),
        (ps, p) -> {
          ps.setString(1, p.payloadRef());
          ps.setString(2, p.txnId());
          ps.setString(3, p.partnerId() == null ? "unknown" : p.partnerId());
          ps.setString(4, p.operation() == null ? "unknown" : p.operation());
          ps.setString(5, p.direction());
          ps.setString(6, p.body());
          ps.setLong(7, p.latencyMs());
        });
  }

  /** Deletes up to {@code limit} rows stored before {@code cutoff}; returns how many went. */
  public int deleteBefore(Instant cutoff, int limit) {
    return jdbc.update(
        "delete from partner_payload where id in (select id from partner_payload where at < ? order by id limit ?)",
        Timestamp.from(cutoff), limit);
  }
}
