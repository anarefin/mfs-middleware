package mfs.mw.orchestrator.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import mfs.mw.orchestrator.dto.TxnDetail.PartnerCallView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code txn.partner_call} (ticket 35, spec 16.2): the partner leg of the saga timeline. Written in
 * the saga's transaction, read by the query API only; the saga never decides anything from it.
 */
@Repository
public class PartnerCallRepository {

  private final JdbcTemplate jdbc;

  public PartnerCallRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** A request sent to the adapter; a resend under the same {@code requestId} keeps the first row. */
  public void sent(String requestId, UUID txnId, String operation, String family, String partnerId, Instant at) {
    jdbc.update(
        "insert into partner_call (request_id, txn_id, operation, family, partner_id, sent_at)"
            + " values (?, ?, ?, ?, ?, ?) on conflict (request_id) do nothing",
        requestId, txnId, operation, family, partnerId, Timestamp.from(at));
  }

  /**
   * The adapter's answer to {@code requestId}. A later answer (the final one after a PENDING)
   * replaces an earlier one; an answer to a request this table never saw changes nothing.
   */
  public void answered(String requestId, String outcome, String partnerRef, String errorCode, Instant at) {
    jdbc.update(
        "update partner_call set outcome = ?, partner_ref = coalesce(?, partner_ref), error_code = ?,"
            + " answered_at = ? where request_id = ?",
        outcome, partnerRef, errorCode, Timestamp.from(at), requestId);
  }

  /** A partner callback (no request of ours): sent and answered at the time it was received. */
  public void callback(
      String inboundId, UUID txnId, String family, String partnerId, String outcome, String partnerRef,
      String errorCode, Instant at) {
    jdbc.update(
        "insert into partner_call (request_id, txn_id, operation, family, partner_id, sent_at, outcome,"
            + " partner_ref, error_code, answered_at) values (?, ?, 'CALLBACK', ?, ?, ?, ?, ?, ?, ?)"
            + " on conflict (request_id) do nothing",
        inboundId, txnId, family, partnerId, Timestamp.from(at), outcome, partnerRef, errorCode,
        Timestamp.from(at));
  }

  /** Every partner call of {@code txnId}, in the order it was made. */
  public List<PartnerCallView> of(UUID txnId) {
    return jdbc.query(
        "select request_id, operation, family, partner_id, sent_at, outcome, partner_ref, error_code,"
            + " answered_at from partner_call where txn_id = ? order by id",
        (rs, i) -> {
          Timestamp answered = rs.getTimestamp("answered_at");
          return new PartnerCallView(
              rs.getString("request_id"), rs.getString("operation"), rs.getString("family"),
              rs.getString("partner_id"), rs.getTimestamp("sent_at").toInstant(), rs.getString("outcome"),
              rs.getString("partner_ref"), rs.getString("error_code"),
              answered == null ? null : answered.toInstant());
        },
        txnId);
  }
}
