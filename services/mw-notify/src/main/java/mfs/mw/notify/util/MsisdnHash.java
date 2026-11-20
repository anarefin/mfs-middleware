package mfs.mw.notify.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Canonical MSISDN and the SHA-256 stored for a promotional opt-out (ticket 111, NOT-09). */
public final class MsisdnHash {

  private MsisdnHash() {}

  /**
   * The 11-digit {@code 01…} form. Accepts {@code 880} / {@code +880} and separators; anything that
   * is not a Bangladesh mobile number is null.
   */
  public static String canonical(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String digits = raw.replaceAll("\\D", "");
    if (digits.startsWith("880") && digits.length() == 13) {
      digits = "0" + digits.substring(3);
    }
    if (digits.length() == 11 && digits.charAt(0) == '0' && digits.charAt(1) == '1'
        && digits.charAt(2) >= '3' && digits.charAt(2) <= '9') {
      return digits;
    }
    return null;
  }

  /** Lower-case hex SHA-256 of the canonical number. */
  public static String sha256(String canonical) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 missing", e);
    }
  }
}
