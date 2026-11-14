package mfs.mw.crypto.exception;

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

  /** No or bad service token on /internal/** (signature, expiry, issuer, audience): 401. */
  public static final String AUTH_UNAUTHENTICATED = "MW-AUTH-001";
  /** A valid service token of a client that may not call: 403. */
  public static final String AUTH_FORBIDDEN = "MW-AUTH-002";

  /** No key with this keyId: 404. */
  public static final String VAL_UNKNOWN_KEY = "MW-VAL-010";
  /** Not a key purpose of spec 18.8 (or a purpose this provider cannot serve): 400. */
  public static final String VAL_UNKNOWN_PURPOSE = "MW-VAL-011";
  /** A key with this keyId exists already: 409. */
  public static final String VAL_KEY_EXISTS = "MW-VAL-012";
  /** The key is symmetric: it has no public key: 400. */
  public static final String VAL_NO_PUBLIC_KEY = "MW-VAL-013";
  /**
   * The PIN block does not decrypt under the transport key, or the PIN in it is not exactly 5 ASCII
   * digits: 400. A wrong PIN is not an error ({@code match: false}).
   */
  public static final String VAL_BAD_PIN_BLOCK = "MW-VAL-014";
  /** The key exists but may not do this (another purpose, or a version no longer ACTIVE): 400. */
  public static final String VAL_KEY_NOT_USABLE = "MW-VAL-015";
  /** The envelope (wrapped key, iv, ciphertext, aad) fails its integrity check: 400. */
  public static final String VAL_BAD_ENVELOPE = "MW-VAL-016";
  /** Key ceremony (ticket 75): a component that is no P-256 scalar or does not match its check value: 400. */
  public static final String VAL_BAD_COMPONENT = "MW-VAL-017";
  /**
   * Key ceremony or approved key change (ticket 75) in the wrong state: another live ceremony of the
   * key, the same custodian twice, a commit before both shares, maker equal to checker, a replay
   * with other values, a version that may not be retired: 409.
   */
  public static final String VAL_KEY_CHANGE_CONFLICT = "MW-VAL-018";
  /**
   * The HSM cannot be reached, refused the login or failed the operation: 503, retryable. The
   * request fails closed (spec 18.8, D23).
   */
  public static final String SYS_HSM_UNAVAILABLE = "MW-SYS-010";

  private ErrorCodes() {}
}
