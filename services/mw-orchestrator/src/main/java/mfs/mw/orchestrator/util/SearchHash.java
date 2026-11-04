package mfs.mw.orchestrator.util;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Keyed search hash (spec 14.3, 16.3 {@code initiator_hash}): HMAC-SHA256 under a key from
 * OpenBao over the canonical form of an id. An MSISDN in any accepted form ({@code 01...}, {@code
 * 880...}, {@code +880...}, with spaces or dashes) is hashed as {@code 01XNNNNNNNN}, so a search
 * finds it however it was written, and the raw number never needs an index.
 */
public final class SearchHash {

  private static final String ALGORITHM = "HmacSHA256";
  private static final int MIN_KEY_LENGTH = 32;
  private static final Pattern SEPARATORS = Pattern.compile("[ -]");
  private static final Pattern MSISDN = Pattern.compile("^(?:\\+?880|0)(1[3-9]\\d{8})$");

  private final SecretKeySpec key;

  /** @param key at least 32 characters; never logged */
  public SearchHash(String key) {
    if (key == null || key.length() < MIN_KEY_LENGTH) {
      throw new IllegalArgumentException("search hash key must have at least " + MIN_KEY_LENGTH + " characters");
    }
    this.key = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), ALGORITHM);
  }

  /** The 32-byte hash of {@code id} in its canonical form. */
  public byte[] of(String id) {
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(key);
      return mac.doFinal(canonical(id).getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 unavailable", e);
    }
  }

  /** An MSISDN as {@code 01XNNNNNNNN}; any other id stripped of surrounding blanks. */
  static String canonical(String id) {
    String trimmed = id == null ? "" : id.strip();
    var m = MSISDN.matcher(SEPARATORS.matcher(trimmed).replaceAll(""));
    return m.matches() ? "0" + m.group(1) : trimmed;
  }
}
