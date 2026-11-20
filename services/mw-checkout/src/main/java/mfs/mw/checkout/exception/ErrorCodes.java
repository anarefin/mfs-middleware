package mfs.mw.checkout.exception;

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

  // ---- web checkout (spec 9.3, ticket 31) ----

  /** No authenticated merchant on a merchant API call (APISIX hmac-auth did not run): 401. */
  public static final String AUTH_NO_MERCHANT = "MW-AUTH-001";
  /** Ticket 99: the signed Date is missing or 5 minutes or more away: 401. */
  public static final String AUTH_STALE_REQUEST = "MW-AUTH-005";
  /** Ticket 99: the signed X-Nonce is missing, malformed or was used by this merchant: 401. */
  public static final String AUTH_NONCE_REUSED = "MW-AUTH-006";
  /** The APISIX consumer is not a configured checkout merchant: 403. */
  public static final String AUTH_UNKNOWN_MERCHANT = "MW-AUTH-002";
  /**
   * No OTP was requested for this session: 401, request one. (Wrong, expired and used-up OTPs, the
   * PIN and its locks are mw-auth's answers, passed through with their codes; ticket 72.)
   */
  public static final String AUTH_OTP_EXPIRED = "MW-AUTH-011";
  /** The session's pay window (15 min) has passed: 410. */
  public static final String VAL_SESSION_EXPIRED = "MW-VAL-020";
  /** The session is not OPEN any more (paying, paid, failed or cancelled): 409. */
  public static final String VAL_SESSION_STATE = "MW-VAL-021";
  /** The session sent its maximum number of OTPs: 429. */
  public static final String VAL_OTP_LIMIT = "MW-VAL-022";
  /** The merchant's order already has a session with another amount: 409. */
  public static final String IDEM_ORDER_CONFLICT = "MW-IDEM-002";
  /** Kafka refused or timed out the payment request; nothing was sent: 503, retryable. */
  public static final String SYS_UNAVAILABLE = "MW-SYS-010";
  /**
   * mw-auth (OTP, PIN transport key, transaction assertion) could not be asked: unreachable, timed
   * out, breaker open, a 5xx or an unreadable answer (ticket 72; fail closed): 503, retryable.
   */
  public static final String SYS_AUTH_UNAVAILABLE = "MW-SYS-012";

  // ---- refunds (ticket 116) ----

  /** Another merchant's payment (the orchestrator's code for the same refusal): 403. */
  public static final String AUTH_NOT_OWNER = "MW-AUTH-022";
  /** The refund's Idempotency-Key was used by this merchant with another body: 409. */
  public static final String IDEM_KEY_CONFLICT = "MW-IDEM-001";

  private ErrorCodes() {}
}
