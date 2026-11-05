package mfs.mw.checkout.util;

import java.util.regex.Pattern;

/**
 * Bangladesh mobile numbers at the app edge. Accepted forms: {@code 01XNNNNNNNN}, {@code
 * 8801XNNNNNNNN} and {@code +8801XNNNNNNNN} (X = 3..9); everything else is rejected. Accepted
 * values are turned into the canonical {@code 01XNNNNNNNN} before they go anywhere, so downstream
 * services and logs only ever see one form (masked by {@link MaskingPolicy}, 18.3).
 */
public final class Msisdn {

  /** Bean Validation pattern for an MSISDN field of the app API. */
  public static final String PATTERN = "01[3-9][0-9]{8}|\\+?8801[3-9][0-9]{8}";

  private static final Pattern ACCEPTED = Pattern.compile(PATTERN);

  private Msisdn() {}

  /** The canonical form {@code 01XNNNNNNNN}, or null when {@code value} is not accepted. */
  public static String canonical(String value) {
    if (value == null || !ACCEPTED.matcher(value).matches()) {
      return null;
    }
    return value.substring(value.length() - 11);
  }
}
