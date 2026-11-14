package mfs.mw.crypto.client;

/** What a {@link CryptoProvider} can do (spec 18.8 operations table). */
public enum Capability {
  /** Wallet PIN: decrypt the RSA-OAEP transport block, HMAC-SHA256 under the PIN key. */
  PIN_HMAC,
  /** JWS sign (ES256, RS256, PS256) and JWKS. */
  JWS,
  /** HMAC sign and verify (partner signatures, webhooks). */
  HMAC,
  /** Deterministic tokenisation under a per-domain HMAC key. */
  TOKENISE,
  /** Envelope encryption: AES-256-GCM data keys wrapped by a KEK. */
  ENVELOPE,
  /** XML-DSig and HTTP-message signatures (RSA-SHA256). */
  XMLDSIG,
  /** ISO 9564 PIN-block translation (payShield). */
  PIN_BLOCK,
  /** PVV generate and verify (payShield). */
  PVV
}
