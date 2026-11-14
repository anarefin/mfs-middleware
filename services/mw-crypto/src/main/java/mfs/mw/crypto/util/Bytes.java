package mfs.mw.crypto.util;

import java.util.Arrays;
import java.util.Base64;
import mfs.mw.crypto.exception.CryptoErrors;

/** Base64 fields of the operation API and zeroing of secret buffers. */
public final class Bytes {

  private static final char[] BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();

  private Bytes() {}

  /** Standard base64 {@code value} of request field {@code field}; bad base64 is {@code MW-VAL-001}. */
  public static byte[] decode(String field, String value) {
    try {
      return Base64.getDecoder().decode(value == null ? "" : value);
    } catch (IllegalArgumentException e) {
      throw CryptoErrors.invalidField(field, "not base64");
    }
  }

  public static String encode(byte[] value) {
    return Base64.getEncoder().encodeToString(value);
  }

  /** Overwrites every buffer with zeros (null is skipped). */
  public static void zero(byte[]... buffers) {
    for (byte[] b : buffers) {
      if (b != null) {
        Arrays.fill(b, (byte) 0);
      }
    }
  }

  /** RFC 4648 base32, upper case, without padding. */
  public static String base32(byte[] value) {
    StringBuilder out = new StringBuilder((value.length * 8 + 4) / 5);
    int buffer = 0;
    int bits = 0;
    for (byte b : value) {
      buffer = (buffer << 8) | (b & 0xff);
      bits += 8;
      while (bits >= 5) {
        out.append(BASE32[(buffer >> (bits - 5)) & 31]);
        bits -= 5;
      }
    }
    if (bits > 0) {
      out.append(BASE32[(buffer << (5 - bits)) & 31]);
    }
    return out.toString();
  }
}
