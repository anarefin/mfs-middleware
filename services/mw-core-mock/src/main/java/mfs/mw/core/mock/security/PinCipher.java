package mfs.mw.core.mock.security;

import java.nio.charset.StandardCharsets;
import java.security.spec.MGF1ParameterSpec;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import mfs.mw.core.mock.dto.EncryptedPin;
import org.springframework.stereotype.Component;

/**
 * Decrypts {@code encryptedPin} (RSA-OAEP, SHA-256 for both the hash and MGF1). Any problem (other
 * key id, bad base64, wrong key, wrong padding) is an empty result: the caller treats it as an
 * invalid PIN block. The plaintext is returned to the caller and never logged here.
 */
@Component
public class PinCipher {

  private final PinKeyProvider keys;

  public PinCipher(PinKeyProvider keys) {
    this.keys = keys;
  }

  public Optional<String> decrypt(EncryptedPin pin) {
    if (pin == null || pin.ciphertext() == null || !keys.keyId().equals(pin.keyId())) {
      return Optional.empty();
    }
    try {
      Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
      cipher.init(
          Cipher.DECRYPT_MODE,
          keys.privateKey(),
          new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
      byte[] plain = cipher.doFinal(Base64.getDecoder().decode(pin.ciphertext()));
      return Optional.of(new String(plain, StandardCharsets.UTF_8));
    } catch (java.security.GeneralSecurityException | IllegalArgumentException e) {
      return Optional.empty();
    }
  }
}
