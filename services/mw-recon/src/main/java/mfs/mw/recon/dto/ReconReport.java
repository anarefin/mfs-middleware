package mfs.mw.recon.dto;

import java.time.LocalDate;
import java.util.List;

/** A recon report (ticket 127, FRS REC-05): count and amount per class for one partner and date range. */
public record ReconReport(String partnerId, LocalDate from, LocalDate to, ReportKind kind, List<Row> rows) {

  /** One class: how many findings and their amount in poisha. */
  public record Row(String reconClass, long count, long amountMinor) {}

  public ReconReport {
    rows = List.copyOf(rows);
  }

  public long totalCount() {
    return rows.stream().mapToLong(Row::count).sum();
  }

  public long totalAmountMinor() {
    return rows.stream().mapToLong(Row::amountMinor).sum();
  }
}
