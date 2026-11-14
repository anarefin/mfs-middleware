package mfs.mw.simulators.client;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * An LMK in this JVM's memory, fresh at every start (host tests; {@code sim.payshield.lmk-store:
 * memory}). The stack keeps the LMK in the SoftHSM {@code paysim} token instead ({@link
 * Pkcs11LmkStore}).
 */
public class InMemoryLmkStore implements LmkStore {

  private final SecretKeySpec lmk;

  public InMemoryLmkStore() {
    byte[] k = new byte[32];
    new SecureRandom().nextBytes(k);
    this.lmk = new SecretKeySpec(k, "AES");
  }

  @Override
  public byte[] encrypt(byte[] data) {
    return run(Cipher.ENCRYPT_MODE, data);
  }

  @Override
  public byte[] decrypt(byte[] data) {
    return run(Cipher.DECRYPT_MODE, data);
  }

  private byte[] run(int mode, byte[] data) {
    try {
      Cipher c = Cipher.getInstance("AES/ECB/NoPadding");
      c.init(mode, lmk);
      return c.doFinal(data);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("LMK cipher failed", e);
    }
  }
}
