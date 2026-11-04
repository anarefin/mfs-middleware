package mfs.mw.adapter.billpay.exception;

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
  /** Unexpected failure; details stay in the log, never in the response. */
  public static final String SYS_INTERNAL = "MW-SYS-001";

  private ErrorCodes() {}
}
