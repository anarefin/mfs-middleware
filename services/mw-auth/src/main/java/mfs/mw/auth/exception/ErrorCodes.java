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

  /** Body over the 64 KiB cap (the same code as the APISIX edge's 413): HTTP 413. */
  public static final String VAL_BODY_TOO_LARGE = "MW-VAL-BODY_TOO_LARGE";

  /** No, bad or expired token: an internal service JWT, or the app's bearer token (401). */
  public static final String AUTH_UNAUTHENTICATED = "MW-AUTH-001";
  /** The calling client may not use this API, or the token carries no wallet / app role (403). */
  public static final String AUTH_FORBIDDEN = "MW-AUTH-002";

  // Device binding, signed requests, sessions and the binding OTP (spec 9.1, 18.6; moved from
  // mw-app-gateway in ticket 68 with the same codes).
  /** X-Device-Id missing or malformed, or not a device bound to this wallet (any more): 401. */
  public static final String AUTH_DEVICE_NOT_BOUND = "MW-AUTH-003";
  /** Request signature headers missing or the signature (or nonce form) does not verify: 401. */
  public static final String AUTH_BAD_SIGNATURE = "MW-AUTH-004";
  /** X-Timestamp unreadable or outside the allowed clock skew (5 min): 401. */
  public static final String AUTH_STALE_TIMESTAMP = "MW-AUTH-005";
  /** X-Nonce already used by this device (a replayed request): 401. */
  public static final String AUTH_REPLAYED = "MW-AUTH-006";
  /** No live session for this device (idle timeout, or ended by another device): 401. */
  public static final String AUTH_NO_SESSION = "MW-AUTH-007";
  /** Wrong device binding OTP: 401. */
  public static final String AUTH_OTP_WRONG = "MW-AUTH-010";
  /** No binding OTP asked for this device, or it expired or was spent: 401, ask for a new one. */
  public static final String AUTH_OTP_EXPIRED = "MW-AUTH-011";
  /** Too many wrong binding OTPs: 401, ask for a new one. */
  public static final String AUTH_OTP_LOCKED = "MW-AUTH-012";
  /** The wallet was sent its maximum of binding OTPs for now: 429. */
  public static final String VAL_OTP_LIMIT = "MW-VAL-022";
  /** Kafka did not take the OTP SMS in time; nothing was sent, the app may retry: 503. */
  public static final String SYS_UNAVAILABLE = "MW-SYS-010";
  /** Valkey (sessions, nonce cache) could not be asked; nothing was done, retryable: 503. */
  public static final String SYS_SESSION_STORE_UNAVAILABLE = "MW-SYS-012";

  // Login and tokens (spec 18.7, ticket 69); the lockout levels of 041/042 (ticket 70, service/PinVerifier).
  /** Wrong PIN (the failure is counted; details: triesLeft): 401. */
  public static final String AUTH_WRONG_PIN = "MW-AUTH-040";
  /** The wallet's PIN is locked for now (details: retryAfter): 423. */
  public static final String AUTH_PIN_LOCKED = "MW-AUTH-041";
  /** The wallet's PIN is locked until a reset: 423. */
  public static final String AUTH_PIN_LOCKED_PERMANENT = "MW-AUTH-042";
  /** The wallet is locked (admin lock) or not active at the core: 403. */
  public static final String AUTH_WALLET_LOCKED = "MW-AUTH-043";
  /** The new PIN breaks a weak-PIN rule (details: the rule): 400. */
  public static final String AUTH_WEAK_PIN = "MW-AUTH-044";
  /** The wallet has no PIN yet (first PIN: OTP login, then POST /auth/v1/pin), or one is required: 403. */
  public static final String AUTH_PIN_NOT_SET = "MW-AUTH-045";
  /** The device is not the wallet's bound device (login, refresh): 401. */
  public static final String AUTH_DEVICE_MISMATCH = "MW-AUTH-047";
  /** A refresh token already rotated or revoked was used again; its whole family is revoked: 401. */
  public static final String AUTH_REFRESH_REUSED = "MW-AUTH-048";

  // PIN reset (ticket 70).
  /** A care reset of the wallet is open: a self-service reset cannot start meanwhile: 409. */
  public static final String VAL_RESET_OPEN = "MW-VAL-030";
  /** The reset is not (or no longer) VERIFIED: pending, rejected, done or expired: 409. */
  public static final String VAL_RESET_NOT_READY = "MW-VAL-031";

  /**
   * mw-crypto not reachable, timed out, circuit open or answered 5xx (spec 18.7: fail closed; 503,
   * retryable).
   */
  public static final String SYS_CRYPTO_UNAVAILABLE = "MW-SYS-050";
  /** mw-crypto refused the call (4xx other than a stale token): a configuration fault, fail closed. */
  public static final String SYS_CRYPTO_REFUSED = "MW-SYS-051";

  private ErrorCodes() {}
}
