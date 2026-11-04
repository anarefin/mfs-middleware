package mfs.mw.ussd.util;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * USSD text on SMPP (spec 9.2): GSM-7 ({@code data_coding} 0, unpacked, one septet per byte) when
 * every character is in the GSM 03.38 default alphabet or its extension table (English), else
 * UCS-2 ({@code data_coding} 8, UTF-16BE: Bangla). Copied from mw-notify's encoder on purpose
 * (spec 4.3 rule 5); a USSD screen is never concatenated.
 */
public final class UssdText {

  public static final byte GSM7 = 0x00;
  public static final byte UCS2 = 0x08;

  private static final byte ESC = 0x1B;

  /** GSM 03.38 default alphabet, index = septet value; index 0x1B (ESC) is skipped. */
  private static final String BASIC =
      "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞ\u001bÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?"
          + "¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà";

  private static final Map<Character, Byte> BASIC_CODES = new HashMap<>();
  private static final Map<Character, Byte> EXTENSION_CODES =
      Map.of('\f', (byte) 0x0A, '^', (byte) 0x14, '{', (byte) 0x28, '}', (byte) 0x29, '\\', (byte) 0x2F,
          '[', (byte) 0x3C, '~', (byte) 0x3D, ']', (byte) 0x3E, '|', (byte) 0x40, '€', (byte) 0x65);
  private static final Map<Integer, Character> EXTENSION_CHARS = new HashMap<>();

  static {
    for (int i = 0; i < BASIC.length(); i++) {
      if (i != ESC) {
        BASIC_CODES.put(BASIC.charAt(i), (byte) i);
      }
    }
    EXTENSION_CODES.forEach((c, b) -> EXTENSION_CHARS.put((int) b, c));
  }

  /** Encoded text: the {@code data_coding} and the {@code short_message} bytes. */
  public record Encoded(byte dataCoding, byte[] bytes) {}

  private UssdText() {}

  public static Encoded encode(String text) {
    String t = text == null ? "" : text;
    for (int i = 0; i < t.length(); i++) {
      char c = t.charAt(i);
      if (!BASIC_CODES.containsKey(c) && !EXTENSION_CODES.containsKey(c)) {
        return new Encoded(UCS2, t.getBytes(StandardCharsets.UTF_16BE));
      }
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream(t.length());
    for (int i = 0; i < t.length(); i++) {
      char c = t.charAt(i);
      Byte basic = BASIC_CODES.get(c);
      if (basic != null) {
        out.write(basic);
      } else {
        out.write(ESC);
        out.write(EXTENSION_CODES.get(c));
      }
    }
    return new Encoded(GSM7, out.toByteArray());
  }

  /** Decodes UCS-2 ({@code data_coding} 8) or unpacked GSM-7 (anything else). */
  public static String decode(byte dataCoding, byte[] bytes) {
    if (bytes == null) {
      return "";
    }
    if (dataCoding == UCS2) {
      return new String(bytes, StandardCharsets.UTF_16BE);
    }
    StringBuilder out = new StringBuilder(bytes.length);
    for (int i = 0; i < bytes.length; i++) {
      int b = bytes[i] & 0x7F;
      if (b == ESC && i + 1 < bytes.length) {
        Character ext = EXTENSION_CHARS.get(bytes[++i] & 0x7F);
        out.append(ext == null ? ' ' : ext);
      } else {
        out.append(BASIC.charAt(b));
      }
    }
    return out.toString();
  }
}
