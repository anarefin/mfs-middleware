package mfs.mw.simulators.util;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * ISO 9564-1 PIN blocks for the payShield simulator (ticket 64): formats 0, 1 and 3 under a triple
 * DES key (8 bytes), format 4 under an AES-128 key (16 bytes, the enciphered PIN field xor the
 * plain PAN field enciphered again). PINs have 4 to 12 digits. A block that does not decode to a
 * valid PIN field throws {@link IllegalArgumentException} (the simulator answers error 15).
 */
public final class PinBlocks {

  private static final HexFormat HEX = HexFormat.of().withUpperCase();
  private static final SecureRandom RANDOM = new SecureRandom();

  /** The ISO formats with their payShield format codes. */
  public enum Format {
    ISO0("01"),
    ISO1("05"),
    ISO3("47"),
    ISO4("48");

    private final String code;

    Format(String code) {
      this.code = code;
    }

    public String code() {
      return code;
    }

    /** The block length in bytes. */
    public int length() {
      return this == ISO4 ? 16 : 8;
    }

    /** The format of payShield code {@code code}, or IllegalArgumentException. */
    public static Format fromCode(String code) {
      for (Format f : values()) {
        if (f.code.equals(code)) {
          return f;
        }
      }
      throw new IllegalArgumentException("unknown PIN block format");
    }
  }

  private PinBlocks() {}

  /** The 12 rightmost account digits without the check digit (left-padded with zeros). */
  public static String account12(String pan) {
    String body = pan.substring(0, pan.length() - 1);
    return body.length() >= 12 ? body.substring(body.length() - 12) : "0".repeat(12 - body.length()) + body;
  }

  /** The clear format 0 block of {@code pin} and {@code pan} (PIN field xor PAN field). */
  public static byte[] clearFormat0(String pin, String pan) {
    return PayShieldCrypto.xor(pinField('0', pin, 'F'), panField(pan));
  }

  /** {@code pin} as a format {@code f} block enciphered under {@code key} for account {@code pan}. */
  public static byte[] encipher(Format f, String pin, String pan, byte[] key) {
    checkPin(pin);
    return switch (f) {
      case ISO0 -> PayShieldCrypto.tdes(key, clearFormat0(pin, pan), true);
      case ISO1 -> PayShieldCrypto.tdes(key, pinField('1', pin, 'R'), true);
      case ISO3 -> PayShieldCrypto.tdes(key, PayShieldCrypto.xor(pinField('3', pin, 'X'), panField(pan)), true);
      case ISO4 -> {
        byte[] plain = format4PinField(pin);
        byte[] a = PayShieldCrypto.aes(key, plain, true);
        yield PayShieldCrypto.aes(key, PayShieldCrypto.xor(a, format4PanField(pan)), true);
      }
    };
  }

  /** The PIN in format {@code f} block {@code block} under {@code key}; IllegalArgumentException if invalid. */
  public static String decipher(Format f, byte[] block, String pan, byte[] key) {
    if (block.length != f.length()) {
      throw new IllegalArgumentException("PIN block length");
    }
    return switch (f) {
      case ISO0 -> readField(PayShieldCrypto.xor(PayShieldCrypto.tdes(key, block, false), panField(pan)), '0', "F");
      case ISO1 -> readField(PayShieldCrypto.tdes(key, block, false), '1', "0123456789ABCDEF");
      case ISO3 -> readField(PayShieldCrypto.xor(PayShieldCrypto.tdes(key, block, false), panField(pan)), '3', "ABCDEF");
      case ISO4 -> {
        byte[] a = PayShieldCrypto.xor(PayShieldCrypto.aes(key, block, false), format4PanField(pan));
        byte[] plain = PayShieldCrypto.aes(key, a, false);
        byte[] first = new byte[8];
        System.arraycopy(plain, 0, first, 0, 8);
        yield readField(first, '4', "A");
      }
    };
  }

  private static void checkPin(String pin) {
    if (pin == null || !pin.matches("[0-9]{4,12}")) {
      throw new IllegalArgumentException("PIN must be 4 to 12 digits");
    }
  }

  /** Control nibble, length nibble, PIN, fill ('F', 'R' random hex, 'X' random A-F) to 16 nibbles. */
  private static byte[] pinField(char control, String pin, char fill) {
    StringBuilder sb = new StringBuilder(16).append(control).append(Integer.toHexString(pin.length()).toUpperCase())
        .append(pin);
    while (sb.length() < 16) {
      sb.append(switch (fill) {
        case 'R' -> "0123456789ABCDEF".charAt(RANDOM.nextInt(16));
        case 'X' -> "ABCDEF".charAt(RANDOM.nextInt(6));
        default -> fill;
      });
    }
    return HEX.parseHex(sb);
  }

  private static byte[] panField(String pan) {
    return HEX.parseHex("0000" + account12(pan));
  }

  private static byte[] format4PinField(String pin) {
    StringBuilder sb = new StringBuilder(16).append('4').append(Integer.toHexString(pin.length()).toUpperCase())
        .append(pin);
    while (sb.length() < 16) {
      sb.append('A');
    }
    byte[] field = new byte[16];
    System.arraycopy(HEX.parseHex(sb), 0, field, 0, 8);
    byte[] random = new byte[8];
    RANDOM.nextBytes(random);
    System.arraycopy(random, 0, field, 8, 8);
    return field;
  }

  /** M (PAN length - 12), the PAN (at least 12 digits, left-padded with zeros), zeros to 32 nibbles. */
  private static byte[] format4PanField(String pan) {
    String p = pan.length() < 12 ? "0".repeat(12 - pan.length()) + pan : pan;
    StringBuilder sb = new StringBuilder(32).append(p.length() - 12).append(p);
    while (sb.length() < 32) {
      sb.append('0');
    }
    return HEX.parseHex(sb);
  }

  private static String readField(byte[] field, char control, String fillChars) {
    String h = HEX.formatHex(field);
    int len = Character.digit(h.charAt(1), 16);
    if (h.charAt(0) != control || len < 4 || len > 12) {
      throw new IllegalArgumentException("PIN block does not hold a valid PIN field");
    }
    String pin = h.substring(2, 2 + len);
    if (!pin.matches("[0-9]+")) {
      throw new IllegalArgumentException("PIN block does not hold a valid PIN field");
    }
    for (int i = 2 + len; i < 16; i++) {
      if (fillChars.indexOf(h.charAt(i)) < 0) {
        throw new IllegalArgumentException("PIN block does not hold a valid PIN field");
      }
    }
    return pin;
  }
}
