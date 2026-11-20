package mfs.mw.portal.api.service;

import mfs.mw.portal.api.dto.BulkFileFormat;
import mfs.mw.portal.api.dto.BulkFile;

/**
 * Reads a bulk disbursement file in either accepted format (ticket 129, CH-POR-01): CSV through
 * {@link BulkCsvParser}, XLSX through {@link BulkXlsxReader}. Both give the same rows under the
 * same rules ({@link BulkRowValidator}): {@code MW-VAL-030} too large, {@code MW-VAL-031} with a
 * detail per problem otherwise.
 */
public class BulkFileParser {

  private final BulkCsvParser csv;
  private final BulkXlsxReader xlsx;

  public BulkFileParser(int maxRows) {
    this.csv = new BulkCsvParser(maxRows);
    this.xlsx = new BulkXlsxReader(maxRows);
  }

  public BulkFile parse(byte[] file, BulkFileFormat format) {
    return format == BulkFileFormat.XLSX ? xlsx.parse(file) : csv.parse(file);
  }
}
