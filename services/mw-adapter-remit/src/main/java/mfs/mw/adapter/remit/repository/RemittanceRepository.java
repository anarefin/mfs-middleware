package mfs.mw.adapter.remit.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Table {@code remittance} (V8): the inbound remittances accepted per partner reference. */
@Repository
public class RemittanceRepository {

  /** A stored remittance: its Upay reference, payload fingerprint and status. */
  public record Stored(String partnerId, String partnerRef, String txnId, String fingerprint, String status) {}

  private final JdbcTemplate jdbc;

  public RemittanceRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Inserts the remittance unless the partner reference exists already; true when inserted. */
  public boolean insertIfAbsent(String partnerId, String partnerRef, String txnId, String fingerprint, Instant now) {
    return jdbc.update(
            "insert into remittance (partner_id, partner_ref, txn_id, fingerprint, status, received_at)"
                + " values (?, ?, ?, ?, 'ACCEPTED', ?) on conflict (partner_id, partner_ref) do nothing",
            partnerId, partnerRef, txnId, fingerprint, Timestamp.from(now))
        == 1;
  }

  /**
   * The orchestrator's outcome of a remittance (ticket 119b: CREDITED, REJECTED or MANUAL_REVIEW); an
   * unknown status is ignored. True when a row changed.
   */
  public boolean updateStatus(String txnId, String status) {
    if (!java.util.Set.of("CREDITED", "REJECTED", "MANUAL_REVIEW").contains(status)) {
      return false;
    }
    return jdbc.update("update remittance set status = ? where txn_id = ? and status <> ?", status, txnId, status) == 1;
  }

  public Optional<Stored> find(String partnerId, String partnerRef) {
    return jdbc
        .query(
            "select partner_id, partner_ref, txn_id, fingerprint, status from remittance"
                + " where partner_id = ? and partner_ref = ?",
            (rs, n) ->
                new Stored(
                    rs.getString("partner_id"),
                    rs.getString("partner_ref"),
                    rs.getString("txn_id"),
                    rs.getString("fingerprint"),
                    rs.getString("status")),
            partnerId,
            partnerRef)
        .stream()
        .findFirst();
  }
}
