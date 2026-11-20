package mfs.mw.portal.api.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import mfs.mw.portal.api.dto.BulkRecord;
import mfs.mw.portal.api.dto.BulkRow;

/**
 * Reads a bulk disbursement CSV (spec 9.4, ticket 33). UTF-8 (a leading byte order mark is
 * dropped), LF or CRLF, header {@code msisdn,amount,reference} (any case), one payee per line;
 * blank lines are skipped, a field may be wrapped in double quotes. The row rules and the
 * all-or-nothing rejection are {@link BulkRowValidator}'s, shared with the XLSX reader (ticket 129).
 */
public class BulkCsvParser {

  /** Most details listed in one rejection; the message still gives the full count. */
  public static final int MAX_REPORTED = BulkRowValidator.MAX_REPORTED;

  private final int maxRows;

  public BulkCsvParser(int maxRows) {
    this.maxRows = maxRows;
  }

  public List<BulkRow> parse(byte[] file) {
    String text = new String(file, StandardCharsets.UTF_8);
    if (text.startsWith("﻿")) {
      text = text.substring(1);
    }
    String[] lines = text.split("\r?\n", -1);
    List<BulkRecord> rows = new ArrayList<>();
    for (int i = 1; i < lines.length; i++) {
      if (!lines[i].isBlank()) {
        if (rows.size() >= maxRows) {
          throw BulkRowValidator.tooLarge(countData(lines), maxRows);
        }
        rows.add(new BulkRecord(i + 1, fields(lines[i])));
      }
    }
    List<String> header = lines.length == 0 ? null : fields(lines[0]);
    return BulkRowValidator.validate(header, rows, maxRows);
  }

  private static long countData(String[] lines) {
    long n = 0;
    for (int i = 1; i < lines.length; i++) {
      if (!lines[i].isBlank()) {
        n++;
      }
    }
    return n;
  }

  private static List<String> fields(String line) {
    List<String> out = new ArrayList<>();
    for (String raw : line.split(",", -1)) {
      String v = raw.strip();
      if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
        v = v.substring(1, v.length() - 1).strip();
      }
      out.add(v);
    }
    return out;
  }
}
