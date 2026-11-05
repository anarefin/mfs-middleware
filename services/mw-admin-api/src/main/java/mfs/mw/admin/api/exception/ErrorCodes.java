package mfs.mw.admin.api.exception;

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
  /** No valid bearer token (missing, expired, bad signature, not realm ops): HTTP 401. */
  public static final String AUTH_UNAUTHENTICATED = "MW-AUTH-001";
  /** Valid token, but no spec 15.4 role that allows this operation: HTTP 403. */
  public static final String AUTH_FORBIDDEN = "MW-AUTH-002";
  /**
   * mw-orchestrator's or mw-audit-dw's query API is unreachable or answered unusably: 503,
   * retryable (the same code mw-portal-api uses for the orchestrator query).
   */
  public static final String SYS_QUERY_UNAVAILABLE = "MW-SYS-040";

  private ErrorCodes() {}
}
