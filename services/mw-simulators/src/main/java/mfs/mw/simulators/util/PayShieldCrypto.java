package mfs.mw.simulators.util;

import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * Crypto primitives of the payShield 10K simulator (ticket 64, spec 18.8): triple DES and AES-128 in
 * ECB (the PIN block and data ciphers), DES odd parity, key check values and the Visa PVV. Test-only
 * code: the clear working keys exist in the simulator's memory while it runs a command.
 */
public final class PayShieldCrypto {

  private static final HexFormat HEX = HexFormat.of().withUpperCase();

  private PayShieldCrypto() {}

  /** Triple DES ECB, no padding, under a double (K1 K2 K1) or triple length key. */
  public static byte[] tdes(byte[] key, byte[] data, boolean encrypt) {
    byte[] k24 = key.length == 16 ? concat(key, Arrays.copyOf(key, 8)) : key;
    try {
      Cipher c = Cipher.getInstance("DESede/ECB/NoPadding");
      c.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, new SecretKeySpec(k24, "DESede"));
      return c.doFinal(data);
    } catch (GeneralSecurityException e) {
      throw new IllegalArgumentException("triple DES failed", e);
    } finally {
      if (k24 != key) {
        Arrays.fill(k24, (byte) 0);
      }
    }
  }

  /** AES ECB, no padding. */
  public static byte[] aes(byte[] key, byte[] data, boolean encrypt) {
    try {
      Cipher c = Cipher.getInstance("AES/ECB/NoPadding");
      c.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
      return c.doFinal(data);
    } catch (GeneralSecurityException e) {
      throw new IllegalArgumentException("AES failed", e);
    }
  }

  /** The triple DES key check value: the first 3 bytes of the zero block encrypted, 6 hex digits. */
  public static String checkValue(byte[] tdesKey) {
    return HEX.formatHex(tdes(tdesKey, new byte[8], true), 0, 3);
  }

  /** The AES key check value: the first 3 bytes of the zero block encrypted (ECB), 6 hex digits. */
  public static String checkValueAes(byte[] aesKey) {
    return HEX.formatHex(aes(aesKey, new byte[16], true), 0, 3);
  }

  /** Whether every byte has odd parity (a DES key). */
  public static boolean oddParity(byte[] key) {
    for (byte b : key) {
      if (Integer.bitCount(b & 0xFF) % 2 == 0) {
        return false;
      }
    }
    return true;
  }

  /** A copy with the low bit of every byte set for odd parity. */
  public static byte[] withOddParity(byte[] key) {
    byte[] out = key.clone();
    for (int i = 0; i < out.length; i++) {
      int b = out[i] & 0xFE;
      out[i] = (byte) (Integer.bitCount(b) % 2 == 0 ? b | 1 : b);
    }
    return out;
  }

  /**
   * The Visa transformation security parameter: the 11 rightmost digits of the account number
   * without its check digit, the PVKI and the first 4 PIN digits (16 digits).
   */
  public static String tsp(String pan, char pvki, String pin) {
    String body = pan.substring(0, pan.length() - 1);
    String eleven = body.length() >= 11 ? body.substring(body.length() - 11) : "0".repeat(11 - body.length()) + body;
    return eleven + pvki + pin.substring(0, 4);
  }

  /** The Visa PVV: the TSP encrypted under the PVK pair, decimalised to 4 digits. */
  public static String pvv(byte[] pvk, String pan, char pvki, String pin) {
    byte[] enc = tdes(pvk, HEX.parseHex(tsp(pan, pvki, pin)), true);
    return decimalise(HEX.formatHex(enc));
  }

  /** The Visa decimalisation: the digits 0-9 left to right, then the letters A-F as 0-5, 4 digits. */
  public static String decimalise(String hex) {
    StringBuilder sb = new StringBuilder(4);
    for (int i = 0; i < hex.length() && sb.length() < 4; i++) {
      char c = hex.charAt(i);
      if (c >= '0' && c <= '9') {
        sb.append(c);
      }
    }
    for (int i = 0; i < hex.length() && sb.length() < 4; i++) {
      char c = Character.toUpperCase(hex.charAt(i));
      if (c >= 'A' && c <= 'F') {
        sb.append((char) ('0' + (c - 'A')));
      }
    }
    return sb.toString();
  }

  /** {@code a} xor {@code b} (same length). */
  public static byte[] xor(byte[] a, byte[] b) {
    byte[] out = new byte[a.length];
    for (int i = 0; i < a.length; i++) {
      out[i] = (byte) (a[i] ^ b[i]);
    }
    return out;
  }

  private static byte[] concat(byte[] a, byte[] b) {
    byte[] out = Arrays.copyOf(a, a.length + b.length);
    System.arraycopy(b, 0, out, a.length, b.length);
    return out;
  }
}
