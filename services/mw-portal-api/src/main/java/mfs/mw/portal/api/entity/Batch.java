package mfs.mw.portal.api.entity;

import java.time.Instant;
import java.util.UUID;

/**
 * Row of {@code bulk.batch} (own schema only); never leaves the service layer. {@code parentTxnId}
 * and {@code orgWallet} are set on approval (ticket 34), {@code completedAt} once every row settled.
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
    Instant completedAt) {

  /** A batch before execution (upload, maker-checker). */
  public Batch(
      UUID batchId, String orgId, BatchStatus status, int rowCount, long totalMinor, String currency,
      String fileSha256, String makerSub, String makerName, String checkerSub, String checkerName,
      String reason, Instant createdAt, Instant decidedAt) {
    this(
        batchId, orgId, status, rowCount, totalMinor, currency, fileSha256, makerSub, makerName, checkerSub,
        checkerName, reason, createdAt, decidedAt, null, null, null);
  }
}
