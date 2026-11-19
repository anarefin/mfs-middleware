package mfs.mw.core.mock.exception;

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
  /** No or bad service token on the core wallet API (spec 18.2). */
  public static final String AUTH_UNAUTHENTICATED = "MW-AUTH-001";
  /** A valid token of a client that may not call the core wallet API (403). */
  public static final String AUTH_FORBIDDEN = "MW-AUTH-002";
  /** mw-auth's transaction assertion missing or refused (D22; mw-auth's code, passed on as it is). */
  public static final String AUTH_ASSERTION = "MW-AUTH-046";
  /** The same txnId and operation with a different body (FRS 7.3, ticket 93). */
  public static final String IDEM_CONFLICT = "MW-IDEM-001";
  /** Prefix of a core refusal: {@code MW-CORE-<reason>} (the core reason set). */
  public static final String CORE_PREFIX = "MW-CORE-";
  /** Unexpected failure; details stay in the log, never in the response. */
  public static final String SYS_INTERNAL = "MW-SYS-001";

  private ErrorCodes() {}
}
