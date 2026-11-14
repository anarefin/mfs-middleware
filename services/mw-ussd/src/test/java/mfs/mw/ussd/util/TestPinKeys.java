package mfs.mw.ussd.util;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.MGF1ParameterSpec;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import mfs.mw.ussd.dto.EncryptedPin;

/** A PIN key pair generated per test run, standing in for the mw-crypto PIN_TRANSPORT key (no key is checked in). */
public final class TestPinKeys {

  private TestPinKeys() {}

  /** A throwaway test key pair standing in for the transport key (never a real key). */
  public static final KeyPair KEYS = generate();

  private static KeyPair generate() {
    try {
      KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
      g.initialize(2048);
      return g.generateKeyPair();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** The test public key as PEM, as mw-auth serves it (GET /auth/v1/pin-key). */
  public static String pem() {
    String b64 =
        Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
            .encodeToString(KEYS.getPublic().getEncoded());
    return "-----BEGIN PUBLIC KEY-----\n" + b64 + "\n-----END PUBLIC KEY-----\n";
  }

  /** What mw-crypto does: RSA-OAEP, SHA-256 and MGF1-SHA-256, with its private key. */
  public static String decrypt(EncryptedPin pin) throws Exception {
    Cipher c = Cipher.getInstance("RSA/ECB/OAEPPadding");
    c.init(
        Cipher.DECRYPT_MODE,
        KEYS.getPrivate(),
        new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
    return new String(c.doFinal(Base64.getDecoder().decode(pin.ciphertext())), StandardCharsets.UTF_8);
  }

}
