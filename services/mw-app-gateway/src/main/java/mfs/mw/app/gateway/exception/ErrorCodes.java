package mfs.mw.app.gateway.exception;

/** Chassis error codes (spec 5.3). Service-specific codes go next to these. */
public final class ErrorCodes {

  /** Bean Validation failed on a body, parameter or path variable. */
  public static final String VAL_INVALID = "MW-VAL-001";
  /** Same idempotency key, other request content (spec 7.3; the stored response, ticket 99). */
  public static final String IDEM_CONFLICT = "MW-IDEM-001";
  /** Body missing or not parseable. */
  public static final String VAL_MALFORMED = "MW-VAL-002";
  /** No such endpoint or resource. */
  public static final String VAL_NOT_FOUND = "MW-VAL-003";
  /** HTTP method not allowed on this endpoint. */
  public static final String VAL_METHOD = "MW-VAL-004";
  /** Content type not supported. */
  public static final String VAL_MEDIA_TYPE = "MW-VAL-005";
  /** Body over the 64 KiB cap (the same code as the APISIX edge's 413): HTTP 413. */
  public static final String VAL_BODY_TOO_LARGE = "MW-VAL-BODY_TOO_LARGE";
  /** Transaction type not offered on this channel (same code as mw-orchestrator). */
  public static final String VAL_UNSUPPORTED_TXN_TYPE = "MW-VAL-010";
  /** The counterparty wallet is the initiator's own wallet (wallet-to-wallet types). */
  public static final String VAL_SELF_COUNTERPARTY = "MW-VAL-012";
  /** No valid bearer token (missing, expired, bad signature, wrong realm): HTTP 401. */
  public static final String AUTH_UNAUTHENTICATED = "MW-AUTH-001";
  /** Valid token, but no wallet (claim msisdn) or a role this endpoint does not serve: HTTP 403. */
  public static final String AUTH_FORBIDDEN = "MW-AUTH-002";
  /** The token is of a session without a PIN (first-PIN OTP login at mw-auth): 403 (ticket 69). */
  public static final String AUTH_PIN_NOT_SET = "MW-AUTH-045";
  /** X-Device-Id missing, or not a device bound to this wallet (any more): HTTP 401. */
  public static final String AUTH_DEVICE_NOT_BOUND = "MW-AUTH-003";
  /** Request signature headers missing or the signature does not verify: HTTP 401. */
  public static final String AUTH_BAD_SIGNATURE = "MW-AUTH-004";
  /** X-Timestamp unreadable or outside the allowed clock skew (±5 min): HTTP 401. */
  public static final String AUTH_STALE_TIMESTAMP = "MW-AUTH-005";
  /** X-Nonce already used by this device (a replayed request): HTTP 401. */
  public static final String AUTH_REPLAYED = "MW-AUTH-006";
  /** No live session for this device (idle timeout, or ended by another device): HTTP 401. */
  public static final String AUTH_NO_SESSION = "MW-AUTH-007";
  /**
   * No answer within the reply budget where there is nothing to finish later (a balance check, ADR
   * D26: no 202); retryable, with a new Idempotency-Key: 504.
   */
  public static final String TMO_NO_ANSWER = "MW-TMO-001";
  /** Kafka did not accept the request in time; nothing was accepted, the app may retry: 503. */
  public static final String SYS_UNAVAILABLE = "MW-SYS-010";
  /** mw-adapter-billpay could not be asked (biller list, bill fetch); retryable: 503. */
  public static final String SYS_BILLPAY_UNAVAILABLE = "MW-SYS-011";
  /** mw-auth (device, session) or Valkey (device key cache, nonces) could not be asked; nothing was done, retryable: 503. */
  public static final String SYS_SESSION_STORE_UNAVAILABLE = "MW-SYS-012";
  /** mw-orchestrator's query API (the wallet history, ADR D25) could not be asked; retryable: 503. */
  public static final String SYS_ORCHESTRATOR_UNAVAILABLE = "MW-SYS-013";
  /** Unexpected failure; details stay in the log, never in the response. */
  public static final String SYS_INTERNAL = "MW-SYS-001";

  private ErrorCodes() {}
}
