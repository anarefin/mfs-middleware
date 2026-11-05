package mfs.mw.adapter.bank.partner;

/**
 * Pluggable partner authentication (spec 12.1). Shipped here: {@code none}, {@code api-key},
 * {@code hmac-sha256} ({@link mfs.mw.adapter.bank.partner.auth.AuthSchemes}). A scheme instance
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
}
