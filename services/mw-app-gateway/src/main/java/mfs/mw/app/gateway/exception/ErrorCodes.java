package mfs.mw.app.gateway.exception;

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
  /** Transaction type not offered on this channel (same code as mw-orchestrator). */
  public static final String VAL_UNSUPPORTED_TXN_TYPE = "MW-VAL-010";
  /** The counterparty wallet is the initiator's own wallet (wallet-to-wallet types). */
  public static final String VAL_SELF_COUNTERPARTY = "MW-VAL-012";
  /** No valid bearer token (missing, expired, bad signature, wrong realm): HTTP 401. */
  public static final String AUTH_UNAUTHENTICATED = "MW-AUTH-001";
  /** Valid token, but no wallet (claim msisdn) or a role this endpoint does not serve: HTTP 403. */
  public static final String AUTH_FORBIDDEN = "MW-AUTH-002";
  /** Kafka did not accept the request in time; nothing was accepted, the app may retry: 503. */
  public static final String SYS_UNAVAILABLE = "MW-SYS-010";
  /** Unexpected failure; details stay in the log, never in the response. */
  public static final String SYS_INTERNAL = "MW-SYS-001";

  private ErrorCodes() {}
}
