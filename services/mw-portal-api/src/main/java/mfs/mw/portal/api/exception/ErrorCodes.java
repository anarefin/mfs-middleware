package mfs.mw.portal.api.exception;

/** Chassis error codes (spec 5.3). Service-specific codes go next to these. */
public final class ErrorCodes {

  /** Bean Validation failed on a body, parameter or path variable. */
  public static final String VAL_INVALID = "MW-VAL-001";
  /** Body missing or not parseable. */
  public static final String VAL_MALFORMED = "MW-VAL-002";
  /** No such endpoint or resource. */
  public static final String VAL_NOT_FOUND = "MW-VAL-003";
  /** HTTP method not allowed on this endpoint. */
  public static final String VAL_METHOD = "MW-VAL-004";
  /** Content type not supported. */
  public static final String VAL_MEDIA_TYPE = "MW-VAL-005";
  /** Bulk file over the row cap (10,000 data rows) or the byte cap: nothing stored (spec 9.4). */
  public static final String VAL_BULK_TOO_LARGE = "MW-VAL-030";
  /** Bulk file with a bad header, no rows or malformed rows; {@code details[]} name each line. */
  public static final String VAL_BULK_INVALID_ROWS = "MW-VAL-031";
  /** The batch is no longer awaiting approval (already approved or rejected): HTTP 409. */
  public static final String VAL_BATCH_NOT_PENDING = "MW-VAL-032";
  /** No valid bearer token (missing, expired, bad signature, wrong realm): HTTP 401. */
  public static final String AUTH_UNAUTHENTICATED = "MW-AUTH-001";
  /** Valid token, but no org (claim orgId) or not the role this operation needs: HTTP 403. */
  public static final String AUTH_FORBIDDEN = "MW-AUTH-002";
  /** Maker-checker: the maker of a batch cannot approve or reject it: HTTP 403. */
  public static final String AUTH_MAKER_IS_CHECKER = "MW-AUTH-030";
  /** Unexpected failure; details stay in the log, never in the response. */
  public static final String SYS_INTERNAL = "MW-SYS-001";

  private ErrorCodes() {}
}
