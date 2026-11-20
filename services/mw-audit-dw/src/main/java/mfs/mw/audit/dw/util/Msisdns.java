package mfs.mw.audit.dw.util;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The one normal form of a Bangladesh MSISDN before it is tokenised (ticket 130): digits only,
 * {@code 880}/{@code +880} replaced by {@code 0}, i.e. {@code 01[3-9]XXXXXXXX}. The projection and
 * the search normalise alike, so the same number always gives the same token.
 */
public final class Msisdns {

  private static final Pattern NORMAL = Pattern.compile("01[3-9]\\d{8}");

  private Msisdns() {}

  /** The normal form, or empty when {@code value} is not an MSISDN. */
  public static Optional<String> normalise(String value) {
    if (value == null) {
      return Optional.empty();
    }
    String digits = value.strip().replaceAll("[\\s.-]", "");
    if (digits.startsWith("+")) {
      digits = digits.substring(1);
    }
    if (digits.startsWith("880")) {
      digits = "0" + digits.substring(3);
    }
    return NORMAL.matcher(digits).matches() ? Optional.of(digits) : Optional.empty();
  }
}
