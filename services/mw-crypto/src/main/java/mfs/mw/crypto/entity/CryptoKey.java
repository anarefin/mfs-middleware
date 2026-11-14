package mfs.mw.crypto.entity;

import java.time.Instant;

/**
 * One version of a key ({@code crypto.key}, spec 18.8): metadata only, the material is in the HSM
 * under {@code hsmLabel}. {@code algorithm} and {@code provider} are the provider SPI's names.
 */
public record CryptoKey(
    String keyId,
    int version,
    String purpose,
    String algorithm,
    String provider,
    String hsmLabel,
    KeyState state,
    Instant createdAt,
    Instant rotatedAt) {

  /** The JWS {@code kid} and HSM label of a version: {@code <keyId>.v<version>}. */
  public static String kid(String keyId, int version) {
    return keyId + ".v" + version;
  }

  public String kid() {
    return kid(keyId, version);
  }

  public CryptoKey withState(KeyState next, Instant at) {
    return new CryptoKey(keyId, version, purpose, algorithm, provider, hsmLabel, next, createdAt, at);
  }
}
