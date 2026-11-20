package mfs.mw.portal.api.entity;

import java.time.Instant;
import java.util.UUID;

/**
 * Row of {@code bulk.batch} (own schema only); never leaves the service layer. {@code parentTxnId}
 * and {@code orgWallet} are set on approval (ticket 34), {@code completedAt} once every row settled.
 * {@code rowCount} is every data row of the file, {@code invalidCount} those that broke a row rule
 * and are never executed, {@code totalMinor} the amount of the valid rows (ticket 129b).
 * {@code retryRound} counts the approved retries of its FAILED rows, {@code retryRequested*} is a
 * maker's retry request awaiting a checker (ticket 129c).
 */
public record Batch(
    UUID batchId,
    String orgId,
    BatchStatus status,
    int rowCount,
    long totalMinor,
    String currency,
    String fileSha256,
    String makerSub,
    String makerName,
    String checkerSub,
    String checkerName,
    String reason,
    Instant createdAt,
    Instant decidedAt,
    UUID parentTxnId,
    String orgWallet,
    Instant completedAt,
    int invalidCount,
    int retryRound,
    String retryRequestedSub,
    String retryRequestedBy,
    Instant retryRequestedAt) {

  /** A batch never retried. */
  public Batch(
      UUID batchId, String orgId, BatchStatus status, int rowCount, long totalMinor, String currency,
      String fileSha256, String makerSub, String makerName, String checkerSub, String checkerName,
      String reason, Instant createdAt, Instant decidedAt, UUID parentTxnId, String orgWallet,
      Instant completedAt, int invalidCount) {
    this(
        batchId, orgId, status, rowCount, totalMinor, currency, fileSha256, makerSub, makerName, checkerSub,
        checkerName, reason, createdAt, decidedAt, parentTxnId, orgWallet, completedAt, invalidCount, 0, null, null,
        null);
  }

  /** A batch without invalid rows. */
  public Batch(
      UUID batchId, String orgId, BatchStatus status, int rowCount, long totalMinor, String currency,
      String fileSha256, String makerSub, String makerName, String checkerSub, String checkerName,
      String reason, Instant createdAt, Instant decidedAt, UUID parentTxnId, String orgWallet,
      Instant completedAt) {
    this(
        batchId, orgId, status, rowCount, totalMinor, currency, fileSha256, makerSub, makerName, checkerSub,
        checkerName, reason, createdAt, decidedAt, parentTxnId, orgWallet, completedAt, 0);
  }

  /** A batch before execution (upload, maker-checker) without invalid rows. */
  public Batch(
      UUID batchId, String orgId, BatchStatus status, int rowCount, long totalMinor, String currency,
      String fileSha256, String makerSub, String makerName, String checkerSub, String checkerName,
      String reason, Instant createdAt, Instant decidedAt) {
    this(
        batchId, orgId, status, rowCount, totalMinor, currency, fileSha256, makerSub, makerName, checkerSub,
        checkerName, reason, createdAt, decidedAt, null, null, null, 0);
  }

  /** The rows that will be executed. */
  public int validCount() {
    return rowCount - invalidCount;
  }
}
