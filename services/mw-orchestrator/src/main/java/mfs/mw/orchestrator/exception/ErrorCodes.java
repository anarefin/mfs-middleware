package mfs.mw.orchestrator.exception;

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
  /** The transaction type has no flow in this release. */
  public static final String VAL_UNSUPPORTED_TXN_TYPE = "MW-VAL-010";
  /** Amount is zero (the gateway rejects it first; this is the backstop). */
  public static final String VAL_AMOUNT = "MW-VAL-011";
  /** Same idempotency key, different request content (spec 7.3). */
  public static final String IDEM_CONFLICT = "MW-IDEM-001";
  /** The core did not answer ValidateTransaction in time; nothing was moved (spec 7.2). */
  public static final String TMO_CORE_VALIDATE = "MW-TMO-001";
  /** Unexpected failure; details stay in the log, never in the response. */
  public static final String SYS_INTERNAL = "MW-SYS-001";

  private ErrorCodes() {}
}
