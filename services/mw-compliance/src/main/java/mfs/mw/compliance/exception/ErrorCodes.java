package mfs.mw.compliance.exception;

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

  // eKYC (ticket 124).
  /** The application is not in a state that allows this (409). */
  public static final String VAL_KYC_STATE = "MW-VAL-020";
  /** Image too large, not base64, or its type not accepted or not what its bytes are (400). */
  public static final String VAL_DOCUMENT = "MW-VAL-021";
  /** Submit before every required image is uploaded (409). */
  public static final String VAL_DOCUMENTS_MISSING = "MW-VAL-022";
  /** No committed consent for the application: the EC is never called (CMP-04; 409). */
  public static final String VAL_CONSENT_MISSING = "MW-VAL-023";
  /** An authentication or token failure on the internal API (401). */
  public static final String AUTH_UNAUTHENTICATED = "MW-AUTH-001";
  /** A caller that may not use this API (403). */
  public static final String AUTH_FORBIDDEN = "MW-AUTH-002";
  /** The EC NID service did not answer in time; the application stays retryable (504). */
  public static final String TMO_EC = "MW-TMO-EC_NID";
  /** The EC NID circuit breaker is open; the application stays retryable (503). */
  public static final String SYS_PARTNER_CIRCUIT_OPEN = "MW-SYS-PARTNER_CIRCUIT_OPEN";
  /** An upstream (EC NID service, token endpoint) failed or answered unexpectedly (503). */
  public static final String SYS_UPSTREAM = "MW-SYS-002";
  /** The core refused to set the KYC level (unknown wallet, policy): the application stays MATCHED (422). */
  public static final String CORE_KYC_LEVEL_REFUSED = "MW-CORE-KYC_LEVEL_REFUSED";
  /** mw-crypto unreachable or failing: fail closed (503). */
  public static final String SYS_CRYPTO_UNAVAILABLE = "MW-SYS-050";
  /** mw-crypto refused the call (policy, bad request). */
  public static final String SYS_CRYPTO_REFUSED = "MW-SYS-051";

  private ErrorCodes() {}
}
