package mfs.mw.adapter.billpay.repository;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Table {@code partner_reference}: one stable partner reference per txnId. */
@Repository
public class PartnerReferenceRepository {

  private final JdbcTemplate jdbc;

  public PartnerReferenceRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Stores {@code candidate} for {@code txnId} unless a reference exists already, and returns the
   * stored one. Concurrent callers for one txnId all get the same value.
   */
  public String findOrCreate(String txnId, String partnerId, String candidate, Instant now) {
    jdbc.update(
        "insert into partner_reference (txn_id, partner_id, partner_ref, created_at)"
            + " values (?, ?, ?, ?) on conflict (txn_id) do nothing",
        txnId,
        partnerId,
        candidate,
        Timestamp.from(now));
    return jdbc.queryForObject(
        "select partner_ref from partner_reference where txn_id = ?", String.class, txnId);
  }
}
