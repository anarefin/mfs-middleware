package mfs.mw.seeder.util;

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
 * Encrypts a PIN as an app does (spec 18.7, 18.8): its ASCII digits, RSA-OAEP with SHA-256 and
 * MGF1-SHA-256, empty label, under the PIN transport public key (PEM SubjectPublicKeyInfo); base64.
 */
public final class PinCipher {

  private PinCipher() {}

  public static String encrypt(String pin, String publicKeyPem) {
    try {
      String b64 = publicKeyPem.replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "")
          .replaceAll("\\s", "");
      PublicKey key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(b64)));
      Cipher c = Cipher.getInstance("RSA/ECB/OAEPPadding");
      c.init(Cipher.ENCRYPT_MODE, key,
          new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
      return Base64.getEncoder().encodeToString(c.doFinal(pin.getBytes(StandardCharsets.US_ASCII)));
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      throw new IllegalStateException("cannot encrypt with the PIN transport key", e);
    }
  }
}
