package mfs.mw.perf.app;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;

/**
 * The PIN encrypted the way an app does it (spec 9.1): RSA-OAEP with SHA-256 (hash and MGF1)
 * under the core's public key, Base64. The middleware only ever sees this ciphertext.
 */
public record PinCipher(String keyId, PublicKey key) {

  public static PinCipher fromPem(String keyId, String pem) {
    String b64 = pem.replaceAll("-----(BEGIN|END) PUBLIC KEY-----", "").replaceAll("\\s", "");
    try {
      return new PinCipher(keyId, KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(b64))));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("bad PIN public key", e);
    }
  }

  public String encrypt(String pin) {
    try {
      Cipher c = Cipher.getInstance("RSA/ECB/OAEPPadding");
      c.init(Cipher.ENCRYPT_MODE, key,
          new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
      return Base64.getEncoder().encodeToString(c.doFinal(pin.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("PIN encryption failed", e);
    }
  }
}
