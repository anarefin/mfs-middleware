package mfs.mw.portal.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import mfs.mw.portal.api.util.Sensitive;
import mfs.mw.portal.api.util.SensitiveType;

/**
 * {@code GET /portal/org/v1/bulk/batches/{batchId}/preview} (ticket 129b): the batch's totals and
 * one page of its rows in file order.
 */
public record BatchPreview(
    String batchId,
    String status,
    int rowCount,
    int validCount,
    int invalidCount,
    Amount totalAmount,
    int offset,
    int limit,
    List<Row> rows) {

  /**
   * One row: its line (header = 1), the MSISDN masked (18.3), amount and reference, and whether it
   * is valid. An invalid row has no MSISDN, an amount and reference only when they passed their
   * rule, and its error codes.
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Row(
      int line,
      @Sensitive(SensitiveType.MSISDN) String msisdn,
      Amount amount,
      String reference,
      boolean valid,
      List<String> errors) {}
}
