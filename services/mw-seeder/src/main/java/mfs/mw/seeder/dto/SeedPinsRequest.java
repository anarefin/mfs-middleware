package mfs.mw.seeder.dto;

import java.util.List;
import mfs.mw.seeder.util.MaskingPolicy;
import mfs.mw.seeder.util.Sensitive;
import mfs.mw.seeder.util.SensitiveType;

/**
 * mw-auth's dev-only {@code POST /internal/v1/seed/pins} body: one encrypted PIN per wallet.
 * {@code appRole} {@code PARTNER} is the seeder's mark that this organisation wallet logs in as a partner.
 */
public record SeedPinsRequest(List<Pin> pins) {

  public record Pin(
      @Sensitive(SensitiveType.MSISDN) String walletId, EncryptedPin encryptedPin, String appRole) {

    public Pin(String walletId, EncryptedPin encryptedPin) {
      this(walletId, encryptedPin, null);
    }
  }

  /** The PIN encrypted RSA-OAEP SHA-256 under the transport key {@code keyId}; never logged. */
  public record EncryptedPin(String keyId, @Sensitive(SensitiveType.SECRET) String ciphertext) {

    @Override
    public String toString() {
      return MaskingPolicy.describe(this);
    }
  }
}
