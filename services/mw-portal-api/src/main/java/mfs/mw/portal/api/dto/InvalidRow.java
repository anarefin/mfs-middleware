package mfs.mw.portal.api.dto;

import java.util.List;

/**
 * A row of a bulk file that breaks a row rule (ticket 129b): kept for the preview, never executed.
 * Its line (header = 1), the amount in poisha and the reference only when they passed their own
 * rule (null otherwise; a value that failed is never kept or echoed, nor is the MSISDN), and its
 * error codes ({@link #MSISDN_INVALID} ...), in column order.
 */
public record InvalidRow(int line, Long amountMinor, String reference, List<String> errors) {

  public static final String MSISDN_INVALID = "MSISDN_INVALID";
  public static final String AMOUNT_INVALID = "AMOUNT_INVALID";
  public static final String REFERENCE_INVALID = "REFERENCE_INVALID";
  public static final String REFERENCE_DUPLICATE = "REFERENCE_DUPLICATE";
  public static final String FIELD_COUNT = "FIELD_COUNT";

  public InvalidRow {
    errors = List.copyOf(errors);
  }
}
