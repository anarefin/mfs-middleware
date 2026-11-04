package mfs.mw.core.mock.security;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import mfs.mw.core.mock.dto.EncryptedPin;

/** What a channel does: RSA-OAEP, SHA-256 (also for MGF1), with the core's exported public key. */
public final class TestPins {

  private TestPins() {}

  public static EncryptedPin encrypt(String publicKeyPem, String keyId, String pin) {
    try {
      byte[] der =
          Base64.getMimeDecoder()
              .decode(publicKeyPem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", ""));
      PublicKey key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
      Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
      cipher.init(
          Cipher.ENCRYPT_MODE,
          key,
          new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
      return new EncryptedPin(
          keyId, Base64.getEncoder().encodeToString(cipher.doFinal(pin.getBytes(StandardCharsets.UTF_8))));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
