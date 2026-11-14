package mfs.mw.auth.exception;

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

  /** No, bad or expired internal service JWT (401). */
  public static final String AUTH_UNAUTHENTICATED = "MW-AUTH-001";
  /** The calling client may not use this API (403). */
  public static final String AUTH_FORBIDDEN = "MW-AUTH-002";

  /**
   * mw-crypto not reachable, timed out, circuit open or answered 5xx (spec 18.7: fail closed; 503,
   * retryable).
   */
  public static final String SYS_CRYPTO_UNAVAILABLE = "MW-SYS-050";
  /** mw-crypto refused the call (4xx other than a stale token): a configuration fault, fail closed. */
  public static final String SYS_CRYPTO_REFUSED = "MW-SYS-051";

  private ErrorCodes() {}
}
