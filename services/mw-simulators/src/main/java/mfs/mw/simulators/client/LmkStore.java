package mfs.mw.simulators.client;

import java.util.HexFormat;

/**
 * Where the payShield simulator's LMK lives (ticket 64, spec 18.8): an AES-256 key that never leaves
 * it. Working keys and PINs exist outside only encrypted under it (AES ECB of whole 16-byte blocks).
 * In the stack the LMK is a token object of the SoftHSM {@code paysim} token ({@link Pkcs11LmkStore});
 * in host tests an in-memory key ({@link InMemoryLmkStore}). A store that cannot reach its HSM throws
 * {@link IllegalStateException}; the simulator then drops the connection, as a dead HSM would.
 */
public interface LmkStore {

  /** AES-ECB encryption of {@code data} (a multiple of 16 bytes) under the LMK. */
  byte[] encrypt(byte[] data);

  /** AES-ECB decryption of {@code data} (a multiple of 16 bytes) under the LMK. */
  byte[] decrypt(byte[] data);

  /** The LMK check value of the {@code NC} answer: the zero block encrypted, first 8 bytes, 16 hex digits. */
  default String checkValue() {
    return HexFormat.of().withUpperCase().formatHex(encrypt(new byte[16]), 0, 8);
  }
}
