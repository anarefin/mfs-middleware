package mfs.mw.ussd.util;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import mfs.mw.ussd.dto.EncryptedPin;

/**
 * This service's own RSA-OAEP helper (spec 9.2, 18.7, D20; ticket 72): the PIN a customer types on
 * USSD is encrypted the moment it is received under the mw-crypto {@code PIN_TRANSPORT} public key
 * (from mw-auth {@code GET /auth/v1/pin-key}, held by {@code service/PinTransport}), the same way
 * the apps do it (OAEP with SHA-256 and MGF1-SHA-256, Base64), so only mw-crypto can read it. The
 * plain PIN lives only in the caller's local variable; it is never stored, logged or returned.
 */
public final class PinCipher {

  private static final OAEPParameterSpec OAEP =
      new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);

  private final String keyId;
  private final PublicKey key;
  private final SecureRandom random = new SecureRandom();

  /**
   * @param keyId the transport key's kid, sent with the ciphertext
   * @param publicKeyPem the transport public key, X.509 SubjectPublicKeyInfo in PEM
   */
  public PinCipher(String keyId, String publicKeyPem) {
    if (keyId == null || keyId.isBlank()) {
      throw new IllegalArgumentException("PIN key id is required");
    }
    this.keyId = keyId;
    this.key = parse(publicKeyPem);
  }

  public String keyId() {
    return keyId;
  }

  public EncryptedPin encrypt(String pin) {
    try {
      Cipher c = Cipher.getInstance("RSA/ECB/OAEPPadding");
      c.init(Cipher.ENCRYPT_MODE, key, OAEP, random);
      byte[] ct = c.doFinal(pin.getBytes(StandardCharsets.UTF_8));
      return new EncryptedPin(keyId, Base64.getEncoder().encodeToString(ct));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("PIN encryption failed", e);
    }
  }

  private static PublicKey parse(String pem) {
    if (pem == null || pem.isBlank()) {
      throw new IllegalArgumentException("PIN public key is required");
    }
    String b64 =
        pem.replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replaceAll("\\s", "");
    try {
      return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(b64)));
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      throw new IllegalArgumentException("PIN public key is not an RSA public key in PEM", e);
    }
  }
}
