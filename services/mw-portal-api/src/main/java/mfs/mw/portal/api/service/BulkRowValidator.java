package mfs.mw.portal.api.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import mfs.mw.portal.api.dto.BulkFile;
import mfs.mw.portal.api.dto.BulkRecord;
import mfs.mw.portal.api.dto.BulkRow;
import mfs.mw.portal.api.dto.ErrorDetail;
import mfs.mw.portal.api.dto.InvalidRow;
import mfs.mw.portal.api.exception.ErrorCodes;
import mfs.mw.portal.api.exception.MwException;
import mfs.mw.portal.api.util.Msisdn;

/**
 * The row rules of a bulk disbursement file, whatever its format (spec 9.4, tickets 33 and 129):
 * header {@code msisdn,amount,reference} (any case), an accepted MSISDN ({@link Msisdn}), an
 * amount in taka above zero with at most two decimals, a reference {@code [A-Za-z0-9._-]{1,32}}
 * unique within the file. More data rows than the cap is {@code MW-VAL-030}; a bad header or no
 * rows is {@code MW-VAL-031}. A bad row no longer refuses the file (ticket 129b): it is kept with
 * its error codes for the preview and never executed. Values are never echoed back.
 */
final class BulkRowValidator {

  /** Most details listed in one rejection; the message still gives the full count. */
  static final int MAX_REPORTED = 100;

  static final List<String> HEADER = List.of("msisdn", "amount", "reference");

  private static final Pattern AMOUNT = Pattern.compile("([0-9]{1,12})(?:\\.([0-9]{1,2}))?");
  private static final Pattern REFERENCE = Pattern.compile("[A-Za-z0-9._-]{1,32}");

  private BulkRowValidator() {}

  static MwException tooLarge(long dataRows, int maxRows) {
    return tooLarge("File has " + dataRows + " rows; at most " + maxRows + " are allowed");
  }

  static MwException tooLarge(String message) {
    return new MwException(ErrorCodes.VAL_BULK_TOO_LARGE, message);
  }

  /**
   * Validates a whole file: {@code header} is line 1 (null when the file is empty), {@code rows}
   * its non-blank data lines in file order. A bad header or no rows refuses the file ({@code
   * MW-VAL-031}); a bad row is kept as an {@link InvalidRow} with its error codes (ticket 129b).
   */
  static BulkFile validate(List<String> header, List<BulkRecord> rows, int maxRows) {
    if (rows.size() > maxRows) {
      throw tooLarge(rows.size(), maxRows);
    }
    List<ErrorDetail> errors = new ArrayList<>();
    int[] errorCount = {0};
    if (header == null || !HEADER.equals(header.stream().map(f -> f.toLowerCase(Locale.ROOT)).toList())) {
      report(errors, errorCount, new ErrorDetail("line 1", "header must be msisdn,amount,reference"));
    }
    if (rows.isEmpty()) {
      report(errors, errorCount, new ErrorDetail("file", "has no rows"));
    }
    if (errorCount[0] > 0) {
      throw invalid(errorCount[0], errors);
    }

    List<BulkRow> valid = new ArrayList<>();
    List<InvalidRow> invalid = new ArrayList<>();
    Map<String, Integer> referenceLines = new HashMap<>();
    for (BulkRecord record : rows) {
      int line = record.line();
      List<String> f = record.fields();
      if (f.size() != 3) {
        invalid.add(new InvalidRow(line, null, null, List.of(InvalidRow.FIELD_COUNT)));
        continue;
      }
      List<String> codes = new ArrayList<>(2);
      String msisdn = Msisdn.canonical(f.get(0));
      if (msisdn == null) {
        codes.add(InvalidRow.MSISDN_INVALID);
      }
      long amount = amountMinor(f.get(1));
      if (amount <= 0) {
        codes.add(InvalidRow.AMOUNT_INVALID);
      }
      String reference = f.get(2);
      if (!REFERENCE.matcher(reference).matches()) {
        codes.add(InvalidRow.REFERENCE_INVALID);
        reference = null;
      } else if (referenceLines.putIfAbsent(reference, line) != null) {
        codes.add(InvalidRow.REFERENCE_DUPLICATE);
      }
      if (codes.isEmpty()) {
        valid.add(new BulkRow(line, msisdn, amount, reference));
      } else {
        invalid.add(new InvalidRow(line, amount > 0 ? amount : null, reference, codes));
      }
    }
    return new BulkFile(valid, invalid);
  }

  /** {@code MW-VAL-031} for a file refused as a whole by one problem (e.g. not a workbook). */
  static MwException invalid(ErrorDetail problem) {
    return invalid(1, List.of(problem));
  }

  private static MwException invalid(int count, List<ErrorDetail> errors) {
    return new MwException(
            ErrorCodes.VAL_BULK_INVALID_ROWS, "File is not valid: " + count + " problem(s); nothing was stored")
        .withDetails(errors);
  }

  static ErrorDetail field(int line, String name, String issue) {
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
}
