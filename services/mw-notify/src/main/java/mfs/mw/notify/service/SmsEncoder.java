package mfs.mw.notify.service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Picks the SMS alphabet and splits long text (spec 10.2): GSM-7 when every character is in the
 * GSM 03.38 default alphabet or its extension table (English), otherwise UCS-2 (Bangla). A text
 * over one SMS is cut into parts of 153 septets or 67 UCS-2 units, each prefixed with the 8-bit
 * reference concatenation UDH ({@code 05 00 03 ref total seq}). An escape pair or a surrogate pair
 * is never split across parts. GSM-7 is sent unpacked, one septet per byte (SMSC default alphabet,
 * {@code data_coding} 0).
 */
public final class SmsEncoder {

  private static final byte ESC = 0x1B;

  /** GSM 03.38 default alphabet, index = septet value; index 0x1B (ESC) is skipped. */
  private static final String BASIC =
      "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞ\u001bÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?"
          + "¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà";

  private static final Map<Character, Byte> BASIC_CODES = new HashMap<>();

  private static final Map<Character, Byte> EXTENSION_CODES =
      Map.of(
          '\f', (byte) 0x0A,
          '^', (byte) 0x14,
          '{', (byte) 0x28,
          '}', (byte) 0x29,
          '\\', (byte) 0x2F,
          '[', (byte) 0x3C,
          '~', (byte) 0x3D,
          ']', (byte) 0x3E,
          '|', (byte) 0x40,
          '€', (byte) 0x65);

  static {
    for (int i = 0; i < BASIC.length(); i++) {
      if (i != ESC) {
        BASIC_CODES.put(BASIC.charAt(i), (byte) i);
      }
    }
  }

  private SmsEncoder() {}

  /** Encodes {@code text}; {@code reference} (0-255) ties the parts of one long SMS together. */
  public static EncodedSms encode(String text, int reference) {
    SmsEncoding encoding = encodingFor(text);
    List<byte[]> units = units(text, encoding);
    int bytes = units.stream().mapToInt(u -> u.length).sum();
    if (bytes <= encoding.singleLimit() * encoding.bytesPerUnit()) {
      return new EncodedSms(encoding, List.of(concat(units)));
    }
    List<List<byte[]>> parts = split(units, encoding);
    List<byte[]> segments = new ArrayList<>(parts.size());
    for (int i = 0; i < parts.size(); i++) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] udh = {0x05, 0x00, 0x03, (byte) reference, (byte) parts.size(), (byte) (i + 1)};
      out.write(udh, 0, udh.length);
      byte[] body = concat(parts.get(i));
      out.write(body, 0, body.length);
      segments.add(out.toByteArray());
    }
    return new EncodedSms(encoding, segments);
  }

  /** Parts {@code text} takes on the air. */
  public static int segmentCount(String text) {
    return encode(text, 0).segments().size();
  }

  /** GSM-7 when every character has a GSM 03.38 code, otherwise UCS-2. */
  public static SmsEncoding encodingFor(String text) {
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (!BASIC_CODES.containsKey(c) && !EXTENSION_CODES.containsKey(c)) {
        return SmsEncoding.UCS2;
      }
    }
    return SmsEncoding.GSM7;
  }

  /** The text as indivisible units: a septet or escape pair, or a UTF-16 char or surrogate pair. */
  private static List<byte[]> units(String text, SmsEncoding encoding) {
    List<byte[]> units = new ArrayList<>(text.length());
    int i = 0;
    while (i < text.length()) {
      char c = text.charAt(i);
      if (encoding == SmsEncoding.GSM7) {
        Byte basic = BASIC_CODES.get(c);
        units.add(basic != null ? new byte[] {basic} : new byte[] {ESC, EXTENSION_CODES.get(c)});
        i++;
      } else {
        int len = Character.isHighSurrogate(c) && i + 1 < text.length() ? 2 : 1;
        units.add(text.substring(i, i + len).getBytes(StandardCharsets.UTF_16BE));
        i += len;
      }
    }
    return units;
  }

  private static List<List<byte[]>> split(List<byte[]> units, SmsEncoding encoding) {
    int limit = encoding.partLimit() * encoding.bytesPerUnit();
    List<List<byte[]>> parts = new ArrayList<>();
    List<byte[]> current = new ArrayList<>();
    int size = 0;
    for (byte[] unit : units) {
      if (size + unit.length > limit) {
        parts.add(current);
        current = new ArrayList<>();
        size = 0;
      }
      current.add(unit);
      size += unit.length;
    }
    parts.add(current);
    return parts;
  }

  private static byte[] concat(List<byte[]> units) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (byte[] u : units) {
      out.write(u, 0, u.length);
    }
    return out.toByteArray();
  }
}
