package mfs.mw.portal.api.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import mfs.mw.portal.api.dto.Amount;
import mfs.mw.portal.api.dto.BatchProgress;
import mfs.mw.portal.api.dto.BulkRow;
import mfs.mw.portal.api.entity.Batch;
import mfs.mw.portal.api.entity.BatchItem;
import mfs.mw.portal.api.entity.BatchStatus;
import mfs.mw.portal.api.entity.ItemStatus;
import mfs.mw.portal.api.entity.PendingChild;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** {@code bulk.batch} and {@code bulk.batch_item} (own schema only, spec 16.2). */
@Repository
public class BatchRepository {

  static final int INSERT_CHUNK = 1000;

  private static final String COLUMNS =
      "batch_id, org_id, status, row_count, total_minor, currency, file_sha256, maker_sub, maker_name,"
          + " checker_sub, checker_name, reason, created_at, decided_at, parent_txn_id, org_wallet, completed_at";

  private final JdbcTemplate jdbc;

  public BatchRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Stores the batch and its rows; call inside one transaction. Item status starts PENDING. */
  public void insert(Batch b, List<BulkRow> rows) {
    jdbc.update(
        "INSERT INTO batch (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        b.batchId(), b.orgId(), b.status().name(), b.rowCount(), b.totalMinor(), b.currency(),
        b.fileSha256(), b.makerSub(), b.makerName(), b.checkerSub(), b.checkerName(), b.reason(),
        ts(b.createdAt()), ts(b.decidedAt()), b.parentTxnId(), b.orgWallet(), ts(b.completedAt()));
    jdbc.batchUpdate(
        "INSERT INTO batch_item (batch_id, line_no, msisdn, amount_minor, reference, status)"
            + " VALUES (?, ?, ?, ?, ?, 'PENDING')",
        rows,
        INSERT_CHUNK,
        (ps, r) -> {
          ps.setObject(1, b.batchId());
          ps.setInt(2, r.line());
          ps.setString(3, r.msisdn());
          ps.setLong(4, r.amountMinor());
          ps.setString(5, r.reference());
        });
  }

  /** The batch when it belongs to this org; another org's batch is not found. */
  public Optional<Batch> find(UUID batchId, String orgId) {
    return jdbc
        .query(
            "SELECT " + COLUMNS + " FROM batch WHERE batch_id = ? AND org_id = ?",
            BatchRepository::map,
            batchId,
            orgId)
        .stream()
        .findFirst();
  }

  /** The org's batches, newest first, optionally of one status. */
  public List<Batch> list(String orgId, BatchStatus status, int limit) {
    if (status == null) {
      return jdbc.query(
          "SELECT " + COLUMNS + " FROM batch WHERE org_id = ? ORDER BY created_at DESC, batch_id DESC LIMIT ?",
          BatchRepository::map, orgId, limit);
    }
    return jdbc.query(
        "SELECT " + COLUMNS
            + " FROM batch WHERE org_id = ? AND status = ? ORDER BY created_at DESC, batch_id DESC LIMIT ?",
        BatchRepository::map, orgId, status.name(), limit);
  }

  /**
   * Records the checker's decision only while the batch awaits approval and the checker is not its
   * maker (one atomic statement, so racing checkers decide once). Returns whether it was recorded.
   */
  public boolean decide(
      UUID batchId, String orgId, BatchStatus decision, String checkerSub, String checkerName,
      String reason, Instant at) {
    return jdbc.update(
            """
            UPDATE batch SET status = ?, checker_sub = ?, checker_name = ?, reason = ?, decided_at = ?
            WHERE batch_id = ? AND org_id = ? AND status = 'PENDING_APPROVAL' AND maker_sub <> ?
            """,
            decision.name(), checkerSub, checkerName, reason, ts(at), batchId, orgId, checkerSub)
        == 1;
  }

  private static Timestamp ts(Instant i) {
    return i == null ? null : Timestamp.from(i);
  }

  private static Instant instant(ResultSet rs, String col) throws SQLException {
    Timestamp t = rs.getTimestamp(col);
    return t == null ? null : t.toInstant();
  }

  private static Batch map(ResultSet rs, int n) throws SQLException {
    return new Batch(
        rs.getObject("batch_id", UUID.class),
        rs.getString("org_id"),
        BatchStatus.valueOf(rs.getString("status")),
        rs.getInt("row_count"),
        rs.getLong("total_minor"),
        rs.getString("currency"),
        rs.getString("file_sha256"),
        rs.getString("maker_sub"),
        rs.getString("maker_name"),
        rs.getString("checker_sub"),
        rs.getString("checker_name"),
        rs.getString("reason"),
        instant(rs, "created_at"),
        instant(rs, "decided_at"),
        rs.getObject("parent_txn_id", UUID.class),
        rs.getString("org_wallet"),
        instant(rs, "completed_at"));
  }

  // --- execution (ticket 34) -------------------------------------------------------------------

  /** On approval: the batch's parent txnId and the wallet its rows are paid from. */
  public void startPayout(UUID batchId, UUID parentTxnId, String orgWallet) {
    jdbc.update(
        "UPDATE batch SET parent_txn_id = ?, org_wallet = ? WHERE batch_id = ?", parentTxnId, orgWallet, batchId);
  }

  /** Pauses a running batch (APPROVED or EXECUTING); false when it is in no such state. */
  public boolean pause(UUID batchId, String orgId) {
    return jdbc.update(
            "UPDATE batch SET status = 'PAUSED' WHERE batch_id = ? AND org_id = ? AND status IN ('APPROVED', 'EXECUTING')",
            batchId, orgId)
        == 1;
  }

  /** Resumes a paused batch; false when it is not paused. */
  public boolean resume(UUID batchId, String orgId) {
    return jdbc.update(
            "UPDATE batch SET status = 'EXECUTING' WHERE batch_id = ? AND org_id = ? AND status = 'PAUSED'",
            batchId, orgId)
        == 1;
  }

  /**
   * Locks the next {@code limit} unsent rows of running batches (oldest approval first, then file
   * order), skipping rows and batches another transaction holds. The batch row is locked too, so a
   * pause waits for this transaction and the dispatcher never sends a row of a batch paused before
   * it read it. Call inside the transaction that marks them sent.
   */
  public List<PendingChild> lockPending(int limit) {
    return jdbc.query(
        """
        SELECT i.batch_id, i.line_no, i.msisdn, i.amount_minor, i.reference,
               b.org_id, b.org_wallet, b.parent_txn_id, b.currency
        FROM batch_item i JOIN batch b ON b.batch_id = i.batch_id
        WHERE i.status = 'PENDING' AND b.status IN ('APPROVED', 'EXECUTING')
        ORDER BY b.decided_at, i.batch_id, i.line_no
        LIMIT ?
        FOR NO KEY UPDATE OF i, b SKIP LOCKED
        """,
        (rs, n) ->
            new PendingChild(
                rs.getObject("batch_id", UUID.class),
                rs.getInt("line_no"),
                rs.getString("msisdn"),
                rs.getLong("amount_minor"),
                rs.getString("reference"),
                rs.getString("org_id"),
                rs.getString("org_wallet"),
                rs.getObject("parent_txn_id", UUID.class),
                rs.getString("currency")),
        limit);
  }

  /** Rows whose child was written to the outbox: SUBMITTED with their txnId. */
  public void markSubmitted(List<PendingChild> rows, List<UUID> txnIds, Instant at) {
    List<Object[]> args = new java.util.ArrayList<>(rows.size());
    for (int k = 0; k < rows.size(); k++) {
      args.add(new Object[] {txnIds.get(k), ts(at), ts(at), rows.get(k).batchId(), rows.get(k).lineNo()});
    }
    jdbc.batchUpdate(
        "UPDATE batch_item SET status = 'SUBMITTED', txn_id = ?, submitted_at = ?, updated_at = ?"
            + " WHERE batch_id = ? AND line_no = ? AND status = 'PENDING'",
        args);
  }

  /** APPROVED batches whose first rows were just sent become EXECUTING. */
  public void markExecuting(java.util.Collection<UUID> batchIds) {
    for (UUID id : batchIds) {
      jdbc.update("UPDATE batch SET status = 'EXECUTING' WHERE batch_id = ? AND status = 'APPROVED'", id);
    }
  }

  /**
   * Settles the row of child {@code txnId} in {@code status}, unless it is already final (FAILED,
   * REJECTED, REVERSED) or COMPLETED and the new state is not REVERSED. Returns its batch when the
   * row changed.
   */
  public Optional<UUID> settle(UUID txnId, ItemStatus status, String errorCode, Instant at) {
    return jdbc.query(
            """
            UPDATE batch_item SET status = ?, error_code = ?, updated_at = ?
            WHERE txn_id = ? AND status NOT IN ('FAILED', 'REJECTED', 'REVERSED')
              AND (status <> 'COMPLETED' OR ? = 'REVERSED') AND status <> ?
            RETURNING batch_id
            """,
            (rs, n) -> rs.getObject("batch_id", UUID.class),
            status.name(), errorCode, ts(at), txnId, status.name(), status.name())
        .stream()
        .findFirst();
  }

  /** Completes a running or paused batch once no row is unsent or running. */
  public boolean completeIfSettled(UUID batchId, Instant at) {
    return jdbc.update(
            """
            UPDATE batch SET status = 'COMPLETED', completed_at = ?
            WHERE batch_id = ? AND status IN ('EXECUTING', 'PAUSED')
              AND NOT EXISTS (SELECT 1 FROM batch_item i
                              WHERE i.batch_id = ? AND i.status IN ('PENDING', 'SUBMITTED'))
            """,
            ts(at), batchId, batchId)
        == 1;
  }

  /** Row counts of the batch and the amount of its COMPLETED rows. */
  public BatchProgress progress(UUID batchId, String currency) {
    return jdbc.queryForObject(
        """
        SELECT count(*) FILTER (WHERE status = 'PENDING') AS pending,
               count(*) FILTER (WHERE status = 'SUBMITTED') AS in_flight,
               count(*) FILTER (WHERE status = 'COMPLETED') AS completed,
               count(*) FILTER (WHERE status IN ('FAILED', 'REJECTED', 'REVERSED')) AS failed,
               count(*) FILTER (WHERE status = 'MANUAL_REVIEW') AS manual_review,
               coalesce(sum(amount_minor) FILTER (WHERE status = 'COMPLETED'), 0) AS paid
        FROM batch_item WHERE batch_id = ?
        """,
        (rs, n) ->
            new BatchProgress(
                rs.getInt("pending"), rs.getInt("in_flight"), rs.getInt("completed"), rs.getInt("failed"),
                rs.getInt("manual_review"), new Amount(rs.getLong("paid"), currency)),
        batchId);
  }

  /** Every row of the batch in file order. */
  public List<BatchItem> items(UUID batchId) {
    return jdbc.query(
        "SELECT batch_id, line_no, msisdn, amount_minor, reference, status, txn_id, error_code"
            + " FROM batch_item WHERE batch_id = ? ORDER BY line_no",
        (rs, n) ->
            new BatchItem(
                rs.getObject("batch_id", UUID.class),
                rs.getInt("line_no"),
                rs.getString("msisdn"),
                rs.getLong("amount_minor"),
                rs.getString("reference"),
                ItemStatus.valueOf(rs.getString("status")),
                rs.getObject("txn_id", UUID.class),
                rs.getString("error_code")),
        batchId);
  }
}
