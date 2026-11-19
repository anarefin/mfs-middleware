package mfs.mw.adapter.telco.partner;

/**
 * Pluggable partner authentication (spec 12.1). Shipped here: {@code none}, {@code api-key},
 * {@code hmac-sha256} ({@link mfs.mw.adapter.telco.partner.auth.AuthSchemes}). A scheme instance
 * carries its non-secret parameters (header names, skew); the secret behind {@link CredentialRef}
 * is resolved at call time and never logged.
 */
public interface AuthScheme {

  /** Scheme name as written in partner config. */
  String name();

  /** Adds the credentials to an outbound request (headers). */
  void apply(OutboundCall call, CredentialRef ref);

  /** Checks an inbound request (callback); false when it is not authentic. */
  boolean verify(InboundCall call, CredentialRef ref);

  /**
   * The replay nonce of an inbound request that {@link #verify} accepted (ticket 99, FRS PLT-008):
   * the caller lets each one through once per partner within {@link #replayWindow}. Empty for a
   * scheme without nonces.
   */
  default java.util.Optional<String> replayNonce(InboundCall call) {
    return java.util.Optional.empty();
  }

  /** How long a nonce must be remembered: as long as its timestamp can still be accepted. */
  default java.time.Duration replayWindow() {
    return java.time.Duration.ZERO;
  }
}
