package mfs.mw.orchestrator.util;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Keyed search hash (spec 14.3, 16.3 {@code initiator_hash}, {@code payee_hash}): HMAC-SHA256
 * under a key from OpenBao over {@code <type>:<canonical id>}. The type is part of the input, so an
 * id of one type never matches the same text of another (an org id written like an MSISDN never
 * finds that customer's transactions). An MSISDN in any accepted form ({@code 01...}, {@code
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

  /**
   * The 32-byte hash of {@code id} of type {@code type} (a party type such as {@code MSISDN} or
   * {@code ORG}, or a payee type of {@link SearchKeys}), the id in its canonical form.
   */
  public byte[] of(String type, String id) {
    if (type == null || type.isBlank()) {
      throw new IllegalArgumentException("search hash type is required");
    }
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(key);
      return mac.doFinal((type.strip() + ":" + canonical(id)).getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 unavailable", e);
    }
  }

  /** The hash of a search key. */
  public byte[] of(SearchKeys.Key key) {
    return of(key.type(), key.id());
  }

  /** An MSISDN as {@code 01XNNNNNNNN}; any other id stripped of surrounding blanks. */
  static String canonical(String id) {
    String trimmed = id == null ? "" : id.strip();
    var m = MSISDN.matcher(SEPARATORS.matcher(trimmed).replaceAll(""));
    return m.matches() ? "0" + m.group(1) : trimmed;
  }
}
