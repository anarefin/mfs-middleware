package mfs.mw.simulators.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import mfs.mw.simulators.client.LmkStore;
import mfs.mw.simulators.util.PayShieldCrypto;
import org.springframework.stereotype.Service;

/**
 * Keys under the LMK for the payShield simulator (ticket 64). A key travels as a 33-character blob:
 * the scheme ({@code U} a double length triple DES key, {@code S} an AES-128 key: the simulator's
 * stand-in for a key block) and 32 hex digits, the key xor a per-type variant, encrypted under the
 * LMK ({@link LmkStore}). The variant binds a blob to its key type as the payShield LMK pairs do:
 * a triple DES blob given as another type unwraps to a key without odd parity (error 10 or 11).
 * PINs under the LMK ({@code JC}, {@code JE}, {@code DG}) are one block the same way.
 */
@Service
public class PayShieldKeys {

  private static final HexFormat HEX = HexFormat.of().withUpperCase();
  private static final SecureRandom RANDOM = new SecureRandom();

  /** The payShield key types the simulator knows, by host command code (TPK and PVK share 002). */
  public enum KeyType {
    ZMK("000"),
    ZPK("001"),
    TPK("002"),
    PVK("002"),
    ZEK("00A");

    private final String code;

    KeyType(String code) {
      this.code = code;
    }

    public String code() {
      return code;
    }

    public static KeyType fromCode(String code) {
      for (KeyType t : values()) {
        if (t.code.equals(code)) {
          return t;
        }
      }
      throw new IllegalArgumentException("unknown key type");
    }
  }

  /** A clear working key and its scheme; the caller zeroes {@code clear}. */
  public record Key(byte[] clear, char scheme) {
    public boolean aes() {
      return scheme == 'S';
    }
  }

  /** A key whose parity is wrong after unwrapping: a corrupt blob or one of another type. */
  public static final class ParityException extends RuntimeException {
    public ParityException() {
      super("key parity error", null, false, false);
    }
  }

  private final LmkStore lmk;

  public PayShieldKeys(LmkStore lmk) {
    this.lmk = lmk;
  }

  /** The LMK check value ({@code NC}). */
  public String lmkCheckValue() {
    return lmk.checkValue();
  }

  /** A new random key of {@code scheme} ({@code U}: odd parity triple DES, {@code S}: AES-128). */
  public static byte[] random(char scheme) {
    byte[] k = new byte[16];
    RANDOM.nextBytes(k);
    return scheme == 'U' ? PayShieldCrypto.withOddParity(k) : k;
  }

  /** {@code clear} of {@code type} under the LMK, as a blob. */
  public String wrap(KeyType type, byte[] clear, char scheme) {
    checkScheme(scheme);
    if (clear.length != 16) {
      throw new IllegalArgumentException("key length");
    }
    byte[] masked = PayShieldCrypto.xor(clear, variant(type.code() + scheme));
    try {
      return scheme + HEX.formatHex(lmk.encrypt(masked));
    } finally {
      Arrays.fill(masked, (byte) 0);
    }
  }

  /** The clear key of blob {@code blob} of {@code type}; {@link ParityException} for a bad triple DES key. */
  public Key unwrap(KeyType type, String blob) {
    if (blob.length() != 33) {
      throw new IllegalArgumentException("key length");
    }
    char scheme = blob.charAt(0);
    checkScheme(scheme);
    byte[] masked = lmk.decrypt(HEX.parseHex(blob, 1, 33));
    byte[] clear = PayShieldCrypto.xor(masked, variant(type.code() + scheme));
    Arrays.fill(masked, (byte) 0);
    if (scheme == 'U' && !PayShieldCrypto.oddParity(clear)) {
      Arrays.fill(clear, (byte) 0);
      throw new ParityException();
    }
    return new Key(clear, scheme);
  }

  /** {@code pin} (4-12 digits) under the LMK: 32 hex digits. */
  public String pinUnderLmk(String pin) {
    byte[] record = new byte[16];
    record[0] = (byte) pin.length();
    System.arraycopy(pin.getBytes(StandardCharsets.US_ASCII), 0, record, 1, pin.length());
    byte[] masked = PayShieldCrypto.xor(record, variant("PIN"));
    Arrays.fill(record, (byte) 0);
    return HEX.formatHex(lmk.encrypt(masked));
  }

  /** The PIN of a {@link #pinUnderLmk} value; IllegalArgumentException when it holds none. */
  public String pinFromLmk(String hex) {
    if (hex.length() != 32) {
      throw new IllegalArgumentException("PIN under LMK length");
    }
    byte[] record = PayShieldCrypto.xor(lmk.decrypt(HEX.parseHex(hex)), variant("PIN"));
    try {
      int len = record[0];
      if (len < 4 || len > 12) {
        throw new IllegalArgumentException("not a PIN under the LMK");
      }
      String pin = new String(record, 1, len, StandardCharsets.US_ASCII);
      if (!pin.matches("[0-9]+")) {
        throw new IllegalArgumentException("not a PIN under the LMK");
      }
      return pin;
    } finally {
      Arrays.fill(record, (byte) 0);
    }
  }

  private static void checkScheme(char scheme) {
    if (scheme != 'U' && scheme != 'S') {
      throw new IllegalArgumentException("key scheme");
    }
  }

  /** The 16-byte variant of {@code name}: SHA-256 of a fixed label, so it needs no key. */
  private static byte[] variant(String name) {
    try {
      byte[] d = MessageDigest.getInstance("SHA-256")
          .digest(("mw-simulators payShield LMK variant " + name).getBytes(StandardCharsets.US_ASCII));
      return Arrays.copyOf(d, 16);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
