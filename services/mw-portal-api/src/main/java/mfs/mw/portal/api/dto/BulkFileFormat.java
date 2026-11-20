package mfs.mw.portal.api.dto;

import java.util.Locale;

/** The formats a bulk disbursement file may be uploaded in (ticket 129, CH-POR-01). */
public enum BulkFileFormat {
  CSV,
  XLSX;

  public static final String CSV_TYPE = "text/csv";
  public static final String XLSX_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

  /** The format of a request {@code Content-Type} (parameters ignored); CSV for anything else. */
  public static BulkFileFormat of(String contentType) {
    if (contentType == null) {
      return CSV;
    }
    String base = contentType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
    return XLSX_TYPE.equals(base) ? XLSX : CSV;
  }
}
