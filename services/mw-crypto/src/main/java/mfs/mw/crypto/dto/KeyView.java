package mfs.mw.crypto.dto;

import java.time.Instant;
import java.util.List;

/**
 * One key and its versions (crypto-internal.v1 {@code Key}); metadata only.
 *
 * @param algorithm {@code RSA_3072}, {@code EC_P256}, {@code HMAC_SHA256} or {@code AES_256}
 * @param provider the provider that holds it ({@code pkcs11})
 */
public record KeyView(String keyId, String purpose, String algorithm, String provider, List<Version> versions) {

  public KeyView {
    versions = List.copyOf(versions);
  }

  /**
   * @param kid the JWS key id and HSM label, {@code <keyId>.v<version>}
   * @param state {@code ACTIVE}, {@code VERIFY_ONLY} or {@code RETIRED}
   * @param rotatedAt when it left ACTIVE (or VERIFY_ONLY), null while ACTIVE
   */
  public record Version(int version, String kid, String state, Instant createdAt, Instant rotatedAt) {}

  /** {@code GET /internal/v1/keys}. */
  public record Page(List<KeyView> items) {
    public Page {
      items = List.copyOf(items);
    }
  }
}
