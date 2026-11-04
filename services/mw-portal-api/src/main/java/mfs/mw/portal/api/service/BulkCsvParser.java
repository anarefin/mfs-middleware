package mfs.mw.portal.api.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import mfs.mw.portal.api.dto.BulkRow;
import mfs.mw.portal.api.dto.ErrorDetail;
import mfs.mw.portal.api.exception.ErrorCodes;
import mfs.mw.portal.api.exception.MwException;
import mfs.mw.portal.api.util.Msisdn;

/**
 * Reads a bulk disbursement CSV (spec 9.4, ticket 33). UTF-8 (a leading byte order mark is
 * dropped), LF or CRLF, header {@code msisdn,amount,reference} (any case), one payee per line;
 * blank lines are skipped, a field may be wrapped in double quotes. Rules per row: an accepted
 * MSISDN ({@link Msisdn}), an amount in taka above zero with at most two decimals, a reference
 * {@code [A-Za-z0-9._-]{1,32}} unique within the file. The file is all or nothing: more data rows
 * than the cap is {@code MW-VAL-030}; any bad row is {@code MW-VAL-031} with one detail per bad
 * field and line (the first {@link #MAX_REPORTED}; the message counts them all). Values are never
 * echoed back, so an MSISDN never appears unmasked in a response or a log.
 */
public class BulkCsvParser {

  /** Most details listed in one rejection; the message still gives the full count. */
  public static final int MAX_REPORTED = 100;

  static final List<String> HEADER = List.of("msisdn", "amount", "reference");

  private static final Pattern AMOUNT = Pattern.compile("([0-9]{1,12})(?:\\.([0-9]{1,2}))?");
  private static final Pattern REFERENCE = Pattern.compile("[A-Za-z0-9._-]{1,32}");

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
    long dataRows = 0;
    for (int i = 1; i < lines.length; i++) {
      if (!lines[i].isBlank()) {
        dataRows++;
      }
    }
    if (dataRows > maxRows) {
      throw new MwException(
          ErrorCodes.VAL_BULK_TOO_LARGE,
          "File has " + dataRows + " rows; at most " + maxRows + " are allowed");
    }

    List<ErrorDetail> errors = new ArrayList<>();
    int[] errorCount = {0};
    if (lines.length == 0 || !HEADER.equals(fields(lines[0]).stream().map(f -> f.toLowerCase(Locale.ROOT)).toList())) {
      report(errors, errorCount, new ErrorDetail("line 1", "header must be msisdn,amount,reference"));
    }
    if (dataRows == 0) {
      report(errors, errorCount, new ErrorDetail("file", "has no rows"));
    }

    List<BulkRow> rows = new ArrayList<>();
    Map<String, Integer> referenceLines = new HashMap<>();
    for (int i = 1; i < lines.length; i++) {
      if (lines[i].isBlank()) {
        continue;
      }
      int line = i + 1;
      List<String> f = fields(lines[i]);
      if (f.size() != 3) {
        report(errors, errorCount, new ErrorDetail("line " + line, "must have 3 fields, has " + f.size()));
        continue;
      }
      boolean ok = true;
      String msisdn = Msisdn.canonical(f.get(0));
      if (msisdn == null) {
        ok = report(errors, errorCount, field(line, "msisdn", "must be a mobile number 01XNNNNNNNN"));
      }
      long amount = amountMinor(f.get(1));
      if (amount <= 0) {
        ok = report(errors, errorCount, field(line, "amount", "must be taka above 0 with at most 2 decimals"));
      }
      String reference = f.get(2);
      if (!REFERENCE.matcher(reference).matches()) {
        ok = report(errors, errorCount, field(line, "reference", "must be 1 to 32 of A-Z a-z 0-9 . _ -"));
      } else {
        Integer first = referenceLines.putIfAbsent(reference, line);
        if (first != null) {
          ok = report(errors, errorCount, field(line, "reference", "duplicate of line " + first));
        }
      }
      if (ok) {
        rows.add(new BulkRow(line, msisdn, amount, reference));
      }
    }
    if (errorCount[0] > 0) {
      throw new MwException(
              ErrorCodes.VAL_BULK_INVALID_ROWS,
              "File is not valid: " + errorCount[0] + " problem(s); nothing was stored")
          .withDetails(errors);
    }
    return rows;
  }

  private static ErrorDetail field(int line, String name, String issue) {
    return new ErrorDetail("line " + line + ": " + name, issue);
  }

  /** Records a problem (listing only the first {@link #MAX_REPORTED}); always false. */
  private static boolean report(List<ErrorDetail> errors, int[] count, ErrorDetail d) {
    count[0]++;
    if (errors.size() < MAX_REPORTED) {
      errors.add(d);
    }
    return false;
  }

  /** Amount in poisha, or -1 when it is not a valid taka amount. */
  private static long amountMinor(String value) {
    var m = AMOUNT.matcher(value);
    if (!m.matches()) {
      return -1;
    }
    String frac = m.group(2) == null ? "" : m.group(2);
    return Long.parseLong(m.group(1)) * 100 + (frac.isEmpty() ? 0 : Long.parseLong(frac.length() == 1 ? frac + "0" : frac));
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
