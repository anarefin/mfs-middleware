package mfs.mw.portal.api.dto;

import java.util.List;

/**
 * A read bulk file (ticket 129b): its valid rows, which alone are executed, and its invalid rows
 * with their error codes, each in file order.
 */
public record BulkFile(List<BulkRow> rows, List<InvalidRow> invalid) {

  public BulkFile {
    rows = List.copyOf(rows);
    invalid = List.copyOf(invalid);
  }

  /** Every data row of the file, valid or not. */
  public int rowCount() {
    return rows.size() + invalid.size();
  }

  /** The amount of the valid rows in poisha (what the batch pays). */
  public long totalMinor() {
    long total = 0;
    for (BulkRow r : rows) {
      total = Math.addExact(total, r.amountMinor());
    }
    return total;
  }
}
