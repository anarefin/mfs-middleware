package mfs.mw.recon.exception;

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
  /** The resource is in a state that does not allow the change (ticket 127: explaining a closed exception); 409. */
  public static final String VAL_STATE_CONFLICT = "MW-VAL-006";
  /** Unexpected failure; details stay in the log, never in the response. */
  public static final String SYS_INTERNAL = "MW-SYS-001";
  /** The trust-bank SFTP host did not answer (ticket 126). */
  public static final String SYS_UPSTREAM = "MW-SYS-002";

  private ErrorCodes() {}
}
