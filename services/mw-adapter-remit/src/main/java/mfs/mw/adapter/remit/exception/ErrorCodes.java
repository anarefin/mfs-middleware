package mfs.mw.adapter.remit.exception;

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

  /** A partner callback whose signature (or timestamp) does not verify (ticket 22). */
  public static final String AUTH_SIGNATURE = "MW-AUTH-001";
  /** A callback could not be written to Kafka: not acknowledged, the partner sends it again. */
  public static final String SYS_EVENT_NOT_WRITTEN = "MW-SYS-EVENT_NOT_WRITTEN";
  /** A re-sent remittance whose partner reference was used for another beneficiary or amount (ticket 119). */
  public static final String IDEM_CONFLICT = "MW-IDEM-002";
  /** A partner used up its beneficiary lookup quota (ticket 119c; the edge's 429 code). */
  public static final String SYS_RATE_LIMITED = "MW-SYS-RATE_LIMITED";
  /** The core wallet API could not answer a lookup (ticket 119c). */
  public static final String SYS_CORE_UNAVAILABLE = "MW-SYS-CORE_UNAVAILABLE";

  private ErrorCodes() {}
}
