package mfs.mw.portal.api.dto;

import java.util.List;

/**
 * One non-blank line of a bulk file before validation (ticket 129): its line in the file (header
 * = 1; the row number in a workbook) and its cell texts, in column order. Values are payee data
 * and are never logged or echoed.
 */
public record BulkRecord(int line, List<String> fields) {

  public BulkRecord {
    fields = List.copyOf(fields);
  }
}
