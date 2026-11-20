package mfs.mw.portal.api.repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import mfs.mw.portal.api.dto.Amount;
import mfs.mw.portal.api.dto.BatchProgress;
import mfs.mw.portal.api.dto.BulkFile;
import mfs.mw.portal.api.dto.BulkRow;
import mfs.mw.portal.api.entity.Batch;
import mfs.mw.portal.api.entity.BatchItem;
import mfs.mw.portal.api.entity.BatchStatus;
import mfs.mw.portal.api.entity.ItemStatus;
import mfs.mw.portal.api.entity.PendingChild;
import mfs.mw.portal.api.entity.SubmittedChild;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** {@code bulk.batch} and {@code bulk.batch_item} (own schema only, spec 16.2). */
@Repository
public class BatchRepository {

  static final int INSERT_CHUNK = 1000;

  private static final String INSERT_COLUMNS =
      "batch_id, org_id, status, row_count, total_minor, currency, file_sha256, maker_sub, maker_name,"
          + " checker_sub, checker_name, reason, created_at, decided_at, parent_txn_id, org_wallet, completed_at,"
          + " invalid_count";

  private static final String COLUMNS =
      INSERT_COLUMNS
          + ", retry_round, retry_requested_sub, retry_requested_name, retry_requested_at, shortfall_minor, blocked_reason";

  private final JdbcTemplate jdbc;

  public BatchRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Stores the batch and its (valid) rows; call inside one transaction. */
  public void insert(Batch b, List<BulkRow> rows) {
    insert(b, new BulkFile(rows, List.of()));
  }

  /**
   * Stores the batch and every row of its file; call inside one transaction. Valid rows start
   * PENDING, invalid rows are INVALID with their error codes (ticket 129b) and are never sent.
   */
  public void insert(Batch b, BulkFile file) {
    jdbc.update(
        "INSERT INTO batch (" + INSERT_COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        b.batchId(), b.orgId(), b.status().name(), b.rowCount(), b.totalMinor(), b.currency(),
        b.fileSha256(), b.makerSub(), b.makerName(), b.checkerSub(), b.checkerName(), b.reason(),
        ts(b.createdAt()), ts(b.decidedAt()), b.parentTxnId(), b.orgWallet(), ts(b.completedAt()),
        b.invalidCount());
    List<BulkRow> rows = file.rows();
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
    jdbc.batchUpdate(
        "INSERT INTO batch_item (batch_id, line_no, amount_minor, reference, status, row_errors)"
            + " VALUES (?, ?, ?, ?, 'INVALID', ?)",
        file.invalid(),
        INSERT_CHUNK,
        (ps, r) -> {
          ps.setObject(1, b.batchId());
          ps.setInt(2, r.line());
          ps.setObject(3, r.amountMinor(), Types.BIGINT);
          ps.setString(4, r.reference());
          ps.setArray(5, ps.getConnection().createArrayOf("text", r.errors().toArray()));
        });
  }

  /** Submits a PREVIEW batch for approval (ticket 129b); false when it is no longer a PREVIEW. */
  public boolean submit(UUID batchId, String orgId) {
    return jdbc.update(
            "UPDATE batch SET status = 'PENDING_APPROVAL' WHERE batch_id = ? AND org_id = ? AND status = 'PREVIEW'",
            batchId, orgId)
        == 1;
  }

  /**
   * One page of the batch's rows in file order (ticket 129b): every row, or only the valid ones
   * ({@code valid} true) or only the invalid ones ({@code valid} false).
   */
  public List<BatchItem> page(UUID batchId, Boolean valid, int offset, int limit) {
    String filter = valid == null ? "" : valid ? " AND status <> 'INVALID'" : " AND status = 'INVALID'";
    return jdbc.query(
        "SELECT " + ITEM_COLUMNS + " FROM batch_item WHERE batch_id = ?" + filter
            + " ORDER BY line_no OFFSET ? LIMIT ?",
        BatchRepository::item, batchId, offset, limit);
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
   * Records the checker's decision only while the batch awaits approval (PENDING_APPROVAL, or
   * BLOCKED_FUNDS approved again) and the checker is not its maker (one atomic statement, so racing
   * checkers decide once). Returns whether it was recorded.
   */
  public boolean decide(
      UUID batchId, String orgId, BatchStatus decision, String checkerSub, String checkerName,
      String reason, Instant at) {
    return jdbc.update(
            """
            UPDATE batch SET status = ?, checker_sub = ?, checker_name = ?, reason = ?, decided_at = ?,
                   shortfall_minor = NULL, blocked_reason = NULL
            WHERE batch_id = ? AND org_id = ? AND status IN ('PENDING_APPROVAL', 'BLOCKED_FUNDS') AND maker_sub <> ?
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
        instant(rs, "completed_at"),
        rs.getInt("invalid_count"),
        rs.getInt("retry_round"),
        rs.getString("retry_requested_sub"),
        rs.getString("retry_requested_name"),
        instant(rs, "retry_requested_at"),
        rs.getObject("shortfall_minor", Long.class),
        rs.getString("blocked_reason"));
  }

  // --- execution (ticket 34) -------------------------------------------------------------------

  /**
   * Blocks a batch for short funds (ticket 129c) while it is in {@code from} (awaiting approval, or
   * APPROVED and not started); false when it moved on meanwhile. At approval only for a checker
   * who is not its maker.
   */
  public boolean blockFunds(UUID batchId, BatchStatus from, String checkerSub, long shortfallMinor, String reason) {
    return jdbc.update(
            """
            UPDATE batch SET status = 'BLOCKED_FUNDS', shortfall_minor = ?, blocked_reason = ?
            WHERE batch_id = ? AND status = ? AND (?::text IS NULL OR maker_sub <> ?::text)
            """,
            shortfallMinor, reason, batchId, from.name(), checkerSub, checkerSub)
        == 1;
  }

  /** An APPROVED batch the dispatcher has not started yet, with the amount of its unsent rows. */
  public record Unstarted(UUID batchId, String orgWallet, long pendingMinor) {}

  /** The APPROVED batches, oldest approval first. */
  public List<Unstarted> unstarted() {
    return jdbc.query(
        """
        SELECT b.batch_id, b.org_wallet,
               coalesce((SELECT sum(i.amount_minor) FROM batch_item i
                         WHERE i.batch_id = b.batch_id AND i.status = 'PENDING'), 0) AS pending_minor
        FROM batch b WHERE b.status = 'APPROVED'
        ORDER BY b.decided_at, b.batch_id
        """,
        (rs, n) -> new Unstarted(rs.getObject("batch_id", UUID.class), rs.getString("org_wallet"), rs.getLong("pending_minor")));
  }

  /** Starts an APPROVED batch whose funds were checked: EXECUTING, so the dispatcher sends its rows. */
  public boolean start(UUID batchId) {
    return jdbc.update("UPDATE batch SET status = 'EXECUTING' WHERE batch_id = ? AND status = 'APPROVED'", batchId) == 1;
  }

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
        SELECT i.batch_id, i.line_no, i.msisdn, i.amount_minor, i.reference, i.attempt,
               b.org_id, b.org_wallet, b.parent_txn_id, b.currency
        FROM batch_item i JOIN batch b ON b.batch_id = i.batch_id
        WHERE i.status = 'PENDING' AND b.status = 'EXECUTING'
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
                rs.getString("currency"),
                rs.getInt("attempt")),
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
   * REJECTED, REVERSED), waits for its retry check (a late record of the failed attempt) or is
   * COMPLETED and the new state is not REVERSED. Returns its batch when the row changed.
   */
  public Optional<UUID> settle(UUID txnId, ItemStatus status, String errorCode, Instant at) {
    return jdbc.query(
            """
            UPDATE batch_item SET status = ?, error_code = ?, updated_at = ?
            WHERE txn_id = ? AND status NOT IN ('FAILED', 'REJECTED', 'REVERSED', 'RETRY_CHECK')
              AND (status <> 'COMPLETED' OR ? = 'REVERSED') AND status <> ?
            RETURNING batch_id
            """,
            (rs, n) -> rs.getObject("batch_id", UUID.class),
            status.name(), errorCode, ts(at), txnId, status.name(), status.name())
        .stream()
        .findFirst();
  }

  /**
   * Completes a running or paused batch once no row is unsent or running: COMPLETED, or
   * COMPLETED_WITH_EXCEPTIONS when a row is MANUAL_REVIEW or UNRESOLVED (M3 gate decision). Returns
   * the status it completed in, or empty when it is not complete (or was already).
   */
  public Optional<BatchStatus> completeIfSettled(UUID batchId, Instant at) {
    return jdbc.query(
            """
            UPDATE batch SET completed_at = ?,
                   status = CASE WHEN EXISTS (SELECT 1 FROM batch_item x
                                              WHERE x.batch_id = ? AND x.status IN ('MANUAL_REVIEW', 'UNRESOLVED'))
                                 THEN 'COMPLETED_WITH_EXCEPTIONS' ELSE 'COMPLETED' END
            WHERE batch_id = ? AND status IN ('EXECUTING', 'PAUSED')
              AND NOT EXISTS (SELECT 1 FROM batch_item i
                              WHERE i.batch_id = ? AND i.status IN ('PENDING', 'SUBMITTED', 'RETRY_CHECK'))
            RETURNING status
            """,
            (rs, n) -> BatchStatus.valueOf(rs.getString("status")),
            ts(at), batchId, batchId, batchId)
        .stream()
        .findFirst();
  }

  // --- expiry and reconcile (M3 gate) ----------------------------------------------------------

  /**
   * Expires up to {@code limit} batches still in {@code from} (PREVIEW or PENDING_APPROVAL) that
   * were uploaded before {@code cutoff} (status EXPIRED, decided_at {@code at}); returns them as
   * they are now. Call inside the transaction that audits them; rows a concurrent checker or maker
   * holds are skipped.
   */
  public List<Batch> expirePending(BatchStatus from, Instant cutoff, Instant at, int limit) {
    return jdbc.query(
        """
        UPDATE batch SET status = 'EXPIRED', decided_at = ?
        WHERE batch_id IN (SELECT batch_id FROM batch
                           WHERE status = ? AND created_at < ?
                           ORDER BY created_at LIMIT ? FOR UPDATE SKIP LOCKED)
          AND status = ?
        RETURNING\s"""
            + COLUMNS,
        BatchRepository::map,
        ts(at), from.name(), ts(cutoff), limit, from.name());
  }

  /**
   * Up to {@code limit} SUBMITTED rows sent before {@code cutoff}, least recently reconciled first,
   * stamped as reconciled at {@code at} so a stuck row does not hold the window on the next run.
   */
  public List<SubmittedChild> claimStaleSubmitted(Instant cutoff, Instant at, int limit) {
    return jdbc.query(
        """
        UPDATE batch_item i SET reconciled_at = ?
        FROM batch b
        WHERE b.batch_id = i.batch_id
          AND (i.batch_id, i.line_no) IN (
            SELECT batch_id, line_no FROM batch_item
            WHERE status = 'SUBMITTED' AND submitted_at < ?
            ORDER BY coalesce(reconciled_at, submitted_at), batch_id, line_no
            LIMIT ? FOR UPDATE SKIP LOCKED)
        RETURNING i.batch_id, i.line_no, i.txn_id, i.submitted_at, b.org_id
        """,
        (rs, n) ->
            new SubmittedChild(
                rs.getObject("batch_id", UUID.class),
                rs.getInt("line_no"),
                rs.getObject("txn_id", UUID.class),
                rs.getString("org_id"),
                instant(rs, "submitted_at")),
        ts(at), ts(cutoff), limit);
  }

  /** A SUBMITTED row given up by the reconcile job; returns its batch when it changed. */
  public Optional<UUID> markUnresolved(UUID txnId, Instant at) {
    return jdbc.query(
            "UPDATE batch_item SET status = 'UNRESOLVED', updated_at = ? WHERE txn_id = ? AND status = 'SUBMITTED'"
                + " RETURNING batch_id",
            (rs, n) -> rs.getObject("batch_id", UUID.class),
            ts(at), txnId)
        .stream()
        .findFirst();
  }

  // --- retry of FAILED rows (ticket 129c) -------------------------------------------------------

  /**
   * Records a maker's request to retry the FAILED rows of a settled batch (COMPLETED or
   * COMPLETED_WITH_EXCEPTIONS); false when the batch is not settled, has no FAILED row or already
   * has a request.
   */
  public boolean requestRetry(UUID batchId, String orgId, String makerSub, String makerName, Instant at) {
    return jdbc.update(
            """
            UPDATE batch SET retry_requested_sub = ?, retry_requested_name = ?, retry_requested_at = ?
            WHERE batch_id = ? AND org_id = ? AND status IN ('COMPLETED', 'COMPLETED_WITH_EXCEPTIONS')
              AND retry_requested_sub IS NULL
              AND EXISTS (SELECT 1 FROM batch_item i WHERE i.batch_id = batch.batch_id AND i.status = 'FAILED')
            """,
            makerSub, makerName, ts(at), batchId, orgId)
        == 1;
  }

  /**
   * Approves the pending retry request of a checker who did not make it, in one statement (so a
   * repeated or racing approval finds nothing): the batch runs again (EXECUTING) in its next retry
   * round and its FAILED rows wait for their retry check. Returns whether it was approved. Call
   * inside the transaction that audits it.
   */
  public boolean approveRetry(UUID batchId, String orgId, String checkerSub, Instant at) {
    boolean approved =
        jdbc.update(
                """
                UPDATE batch SET status = 'EXECUTING', completed_at = NULL, retry_round = retry_round + 1,
                       retry_requested_sub = NULL, retry_requested_name = NULL, retry_requested_at = NULL
                WHERE batch_id = ? AND org_id = ? AND status IN ('COMPLETED', 'COMPLETED_WITH_EXCEPTIONS')
                  AND retry_requested_sub IS NOT NULL AND retry_requested_sub <> ?
                """,
                batchId, orgId, checkerSub)
            == 1;
    if (approved) {
      jdbc.update(
          "UPDATE batch_item SET status = 'RETRY_CHECK', updated_at = ? WHERE batch_id = ? AND status = 'FAILED'",
          ts(at), batchId);
    }
    return approved;
  }

  /** A row waiting for its retry check, with the failed attempt to look up. */
  public record RetryCandidate(UUID batchId, int lineNo, String orgId, UUID txnId, int attempt) {}

  /** Up to {@code limit} rows waiting for their retry check, oldest batch first, in file order. */
  public List<RetryCandidate> retryCandidates(int limit) {
    return jdbc.query(
        """
        SELECT i.batch_id, i.line_no, b.org_id, i.txn_id, i.attempt
        FROM batch_item i JOIN batch b ON b.batch_id = i.batch_id
        WHERE i.status = 'RETRY_CHECK'
        ORDER BY i.batch_id, i.line_no
        LIMIT ?
        """,
        (rs, n) ->
            new RetryCandidate(
                rs.getObject("batch_id", UUID.class),
                rs.getInt("line_no"),
                rs.getString("org_id"),
                rs.getObject("txn_id", UUID.class),
                rs.getInt("attempt")),
        limit);
  }

  /**
   * The failed attempt {@code attempt} of a RETRY_CHECK row posted nothing: the row is unsent again
   * as attempt + 1, without the old txnId (so a late record of the failed attempt finds no row).
   * Only from that attempt, so the attempt number moves once. Returns whether it changed.
   */
  public boolean retryCleared(UUID batchId, int lineNo, int attempt, Instant at) {
    return jdbc.update(
            """
            UPDATE batch_item SET status = 'PENDING', attempt = attempt + 1, txn_id = NULL, error_code = NULL,
                   submitted_at = NULL, reconciled_at = NULL, updated_at = ?
            WHERE batch_id = ? AND line_no = ? AND status = 'RETRY_CHECK' AND attempt = ?
            """,
            ts(at), batchId, lineNo, attempt)
        == 1;
  }

  /** The failed attempt of a RETRY_CHECK row may have posted: the row stays FAILED, never resent. */
  public boolean retryRefused(UUID batchId, int lineNo, Instant at) {
    return jdbc.update(
            "UPDATE batch_item SET status = 'FAILED', updated_at = ? WHERE batch_id = ? AND line_no = ? AND status = 'RETRY_CHECK'",
            ts(at), batchId, lineNo)
        == 1;
  }

  /** Row counts of the batch and the amount of its COMPLETED rows. */
  public BatchProgress progress(UUID batchId, String currency) {
    return jdbc.queryForObject(
        """
        SELECT count(*) FILTER (WHERE status IN ('PENDING', 'RETRY_CHECK')) AS pending,
               count(*) FILTER (WHERE status = 'SUBMITTED') AS in_flight,
               count(*) FILTER (WHERE status = 'COMPLETED') AS completed,
               count(*) FILTER (WHERE status IN ('FAILED', 'REJECTED', 'REVERSED')) AS failed,
               count(*) FILTER (WHERE status = 'MANUAL_REVIEW') AS manual_review,
               count(*) FILTER (WHERE status = 'UNRESOLVED') AS unresolved,
               coalesce(sum(amount_minor) FILTER (WHERE status = 'COMPLETED'), 0) AS paid
        FROM batch_item WHERE batch_id = ?
        """,
        (rs, n) ->
            new BatchProgress(
                rs.getInt("pending"), rs.getInt("in_flight"), rs.getInt("completed"), rs.getInt("failed"),
                rs.getInt("manual_review"), rs.getInt("unresolved"), new Amount(rs.getLong("paid"), currency)),
        batchId);
  }

  /** Every row of the batch in file order. */
  public List<BatchItem> items(UUID batchId) {
    return jdbc.query(
        "SELECT " + ITEM_COLUMNS + " FROM batch_item WHERE batch_id = ? ORDER BY line_no",
        BatchRepository::item,
        batchId);
  }

  private static final String ITEM_COLUMNS =
      "batch_id, line_no, msisdn, amount_minor, reference, status, txn_id, error_code, row_errors";

  private static BatchItem item(ResultSet rs, int n) throws SQLException {
    Array errors = rs.getArray("row_errors");
    return new BatchItem(
        rs.getObject("batch_id", UUID.class),
        rs.getInt("line_no"),
        rs.getString("msisdn"),
        rs.getObject("amount_minor", Long.class),
        rs.getString("reference"),
        ItemStatus.valueOf(rs.getString("status")),
        rs.getObject("txn_id", UUID.class),
        rs.getString("error_code"),
        errors == null ? null : List.of((String[]) errors.getArray()));
  }
}
