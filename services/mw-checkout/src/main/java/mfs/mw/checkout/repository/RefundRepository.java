package mfs.mw.checkout.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import mfs.mw.checkout.entity.Refund;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code checkout.refund} (ticket 116): one row per merchant refund. */
@Repository
public class RefundRepository {

  private static final String COLUMNS =
      "refund_id, session_id, merchant_id, idem_key, request_hash, amount_minor, currency, original_txn_id,"
          + " reason, status, error_code, created_at, updated_at";

  private final JdbcClient jdbc;

  public RefundRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Inserts a refund; false when the merchant already used the idempotency key. */
  public boolean insert(Refund r) {
    try {
      jdbc.sql("insert into refund (" + COLUMNS + ") values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
          .params(
              r.refundId(), r.sessionId(), r.merchantId(), r.idemKey(), r.requestHash(), r.amountMinor(),
              r.currency(), r.originalTxnId(), r.reason(), r.status().name(), r.errorCode(), ts(r.createdAt()),
              ts(r.updatedAt()))
          .update();
      return true;
    } catch (DuplicateKeyException e) {
      return false;
    }
  }

  public Optional<Refund> findById(UUID refundId) {
    return jdbc.sql("select " + COLUMNS + " from refund where refund_id = ?")
        .param(refundId)
        .query(RefundRepository::map)
        .optional();
  }

  public Optional<Refund> findByKey(String merchantId, String idemKey) {
    return jdbc.sql("select " + COLUMNS + " from refund where merchant_id = ? and idem_key = ?")
        .params(merchantId, idemKey)
        .query(RefundRepository::map)
        .optional();
  }

  /** A session's refunds, oldest first. */
  public List<Refund> findBySession(UUID sessionId) {
    return jdbc.sql("select " + COLUMNS + " from refund where session_id = ? order by created_at, refund_id")
        .param(sessionId)
        .query(RefundRepository::map)
        .list();
  }

  /** PENDING -> COMPLETED/REJECTED; false when it was already final (the first outcome wins). */
  public boolean finish(UUID refundId, Refund.Status status, String errorCode, Instant now) {
    return jdbc.sql(
                "update refund set status = ?, error_code = ?, updated_at = ?"
                    + " where refund_id = ? and status = 'PENDING'")
            .params(status.name(), errorCode, ts(now), refundId)
            .update()
        == 1;
  }

  /** Removes a refund whose request was never sent (Kafka refused it), so its key can be used again. */
  public void deleteUnsent(UUID refundId) {
    jdbc.sql("delete from refund where refund_id = ? and status = 'PENDING'").param(refundId).update();
  }

  private static Refund map(ResultSet rs, int row) throws SQLException {
    return new Refund(
        rs.getObject("refund_id", UUID.class),
        rs.getObject("session_id", UUID.class),
        rs.getString("merchant_id"),
        rs.getString("idem_key"),
        rs.getString("request_hash"),
        rs.getLong("amount_minor"),
        rs.getString("currency"),
        rs.getObject("original_txn_id", UUID.class),
        rs.getString("reason"),
        Refund.Status.valueOf(rs.getString("status")),
        rs.getString("error_code"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }

  private static Timestamp ts(Instant i) {
    return i == null ? null : Timestamp.from(i);
  }
}
