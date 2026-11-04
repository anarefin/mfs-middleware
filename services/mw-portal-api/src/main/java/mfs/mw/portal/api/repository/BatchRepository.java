package mfs.mw.portal.api.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import mfs.mw.portal.api.dto.BulkRow;
import mfs.mw.portal.api.entity.Batch;
import mfs.mw.portal.api.entity.BatchStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** {@code bulk.batch} and {@code bulk.batch_item} (own schema only, spec 16.2). */
@Repository
public class BatchRepository {

  static final int INSERT_CHUNK = 1000;

  private static final String COLUMNS =
      "batch_id, org_id, status, row_count, total_minor, currency, file_sha256, maker_sub, maker_name,"
          + " checker_sub, checker_name, reason, created_at, decided_at";

  private final JdbcTemplate jdbc;

  public BatchRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Stores the batch and its rows; call inside one transaction. Item status starts PENDING. */
  public void insert(Batch b, List<BulkRow> rows) {
    jdbc.update(
        "INSERT INTO batch (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        b.batchId(), b.orgId(), b.status().name(), b.rowCount(), b.totalMinor(), b.currency(),
        b.fileSha256(), b.makerSub(), b.makerName(), b.checkerSub(), b.checkerName(), b.reason(),
        ts(b.createdAt()), ts(b.decidedAt()));
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
        instant(rs, "decided_at"));
  }
}
