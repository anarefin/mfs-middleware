package mfs.mw.crypto.dto;

import java.util.List;
import java.util.Map;

/**
 * {@code GET /internal/v1/keys/{keyId}/public}: the public key of every version that is still used
 * (ACTIVE and VERIFY_ONLY), as PEM and as JWK, plus those JWKs as one JWK Set (a JWKS endpoint, e.g.
 * mw-auth's, serves {@code jwks} as it is).
 */
public record PublicKeyView(
    String keyId, String purpose, String algorithm, List<Version> versions, Map<String, Object> jwks) {

  public PublicKeyView {
    versions = List.copyOf(versions);
    jwks = Map.copyOf(jwks);
  }

  /**
   * @param pem SubjectPublicKeyInfo, {@code -----BEGIN PUBLIC KEY-----}
   * @param jwk RFC 7517 public JWK with {@code kid}, {@code alg} and {@code use}
   */
  public record Version(int version, String kid, String state, String pem, Map<String, Object> jwk) {
    public Version {
      jwk = Map.copyOf(jwk);
    }
  }
}
