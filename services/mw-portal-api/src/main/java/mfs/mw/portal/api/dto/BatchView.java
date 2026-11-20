package mfs.mw.portal.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/**
 * A bulk batch as the portal sees it (contract {@code api/mw-portal-api/portal-org.v1.openapi.yaml}).
 * {@code parentTxnId} and {@code progress} appear once approved, {@code completedAt} once every row
 * settled (ticket 34). {@code rowCount} is every data row of the file, {@code validCount} those that
 * will be executed, {@code invalidCount} those that broke a row rule, {@code totalAmount} the amount
 * of the valid rows; {@code rowErrors} (the first invalid rows with their codes) only answers the
 * upload (ticket 129b).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BatchView(
    String batchId,
    String orgId,
    String status,
    int rowCount,
    Amount totalAmount,
    String makerId,
    String checkerId,
    String reason,
    Instant createdAt,
    Instant decidedAt,
    String parentTxnId,
    BatchProgress progress,
    Instant completedAt,
    int validCount,
    int invalidCount,
    List<RowError> rowErrors) {

  /** An invalid row of the upload: its line (header = 1) and error codes. */
  public record RowError(int line, List<String> errors) {}

  /** The same view with the upload's row errors. */
  public BatchView withRowErrors(List<RowError> errors) {
    return new BatchView(
        batchId, orgId, status, rowCount, totalAmount, makerId, checkerId, reason, createdAt, decidedAt,
        parentTxnId, progress, completedAt, validCount, invalidCount, errors);
  }
}
