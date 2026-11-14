package mfs.mw.ussd.exception;

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
  /** Body over the service's own cap (the edge's code for the same refusal): 413. */
  public static final String VAL_BODY_TOO_LARGE = "MW-VAL-BODY_TOO_LARGE";
  /** Unexpected failure; details stay in the log, never in the response. */
  public static final String SYS_INTERNAL = "MW-SYS-001";

  /** No menu is live for the MNO, or a session's menu version is gone. */
  public static final String SYS_NO_MENU = "MW-SYS-020";

  /**
   * mw-auth (txn authorisation, PIN transport key) cannot be asked: unreachable, timed out, breaker
   * open, a 5xx or an unreadable answer (fail closed; ticket 72).
   */
  public static final String SYS_AUTH_UNAVAILABLE = "MW-SYS-012";

  /** mw-auth's answers the USSD engine acts on (ticket 72; codes owned by mw-auth). */
  public static final String AUTH_WRONG_PIN = "MW-AUTH-040";
  public static final String AUTH_PIN_LOCKED = "MW-AUTH-041";
  public static final String AUTH_PIN_LOCKED_PERMANENT = "MW-AUTH-042";
  public static final String AUTH_WALLET_LOCKED = "MW-AUTH-043";
  /** mw-crypto refused the PIN block (mw-auth's code): with a stale transport key id, refetch it. */
  public static final String SYS_CRYPTO_REFUSED = "MW-SYS-051";

  private ErrorCodes() {}
}
