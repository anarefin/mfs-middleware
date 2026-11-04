package mfs.mw.app.gateway.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import mfs.mw.app.gateway.dto.TransactionView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** {@code app_transaction}: the latest view of each app transaction (own schema only). */
@Repository
public class AppTransactionRepository {

  private final JdbcTemplate jdbc;
  private final JsonMapper json;

  public AppTransactionRepository(JdbcTemplate jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  /**
   * Inserts or replaces the row unless the stored one is newer: status records of one txnId come
   * in order on one partition, but the accepted row and a retried record may race.
   */
  public void upsert(String initiatorId, String channel, TransactionView view, Instant updatedAt) {
    jdbc.update(
        """
        INSERT INTO app_transaction (txn_id, initiator_id, channel, status, view, updated_at)
        VALUES (?::uuid, ?, ?, ?, ?::jsonb, ?)
        ON CONFLICT (txn_id) DO UPDATE
          SET status = excluded.status, view = excluded.view, updated_at = excluded.updated_at
          WHERE app_transaction.updated_at <= excluded.updated_at
        """,
        view.txnId(), initiatorId, channel, view.status(), json.writeValueAsString(view),
        Timestamp.from(updatedAt));
  }

  /** Inserts the row of a just accepted request; a status record that got there first wins. */
  public void insertAccepted(String initiatorId, String channel, TransactionView view, Instant at) {
    jdbc.update(
        """
        INSERT INTO app_transaction (txn_id, initiator_id, channel, status, view, updated_at)
        VALUES (?::uuid, ?, ?, ?, ?::jsonb, ?)
        ON CONFLICT (txn_id) DO NOTHING
        """,
        view.txnId(), initiatorId, channel, view.status(), json.writeValueAsString(view),
        Timestamp.from(at));
  }

  /**
   * Links the txnId minted for a repeated Idempotency-Key to the transaction stored under the key
   * (the orchestrator's duplicate answer). The row of the repeat is created when the answer
   * outruns {@link #insertAccepted}, which then leaves it alone; its view is the answer's view of
   * the stored transaction, shown only while the stored row itself is missing.
   */
  public void linkRepeat(
      UUID requestTxnId, UUID storedTxnId, String initiatorId, String channel, TransactionView storedView,
      Instant at) {
    jdbc.update(
        """
        INSERT INTO app_transaction (txn_id, initiator_id, channel, status, view, updated_at, stored_txn_id)
        VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)
        ON CONFLICT (txn_id) DO UPDATE SET stored_txn_id = excluded.stored_txn_id
          WHERE app_transaction.initiator_id = excluded.initiator_id
        """,
        requestTxnId, initiatorId, channel, storedView.status(), json.writeValueAsString(storedView),
        Timestamp.from(at), storedTxnId);
  }

  /**
   * The view of {@code txnId} if it belongs to {@code initiatorId}; for the txnId of a repeated
   * request linked by {@link #linkRepeat}, the stored transaction's view.
   */
  public Optional<TransactionView> find(UUID txnId, String initiatorId) {
    return jdbc
        .query(
            """
            SELECT coalesce(s.view, a.view)::text
              FROM app_transaction a
              LEFT JOIN app_transaction s ON s.txn_id = a.stored_txn_id AND s.initiator_id = a.initiator_id
             WHERE a.txn_id = ? AND a.initiator_id = ?
            """,
            (rs, n) -> json.readValue(rs.getString(1), TransactionView.class),
            txnId, initiatorId)
        .stream()
        .findFirst();
  }
}
