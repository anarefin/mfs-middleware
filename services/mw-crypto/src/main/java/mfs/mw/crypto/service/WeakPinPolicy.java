package mfs.mw.crypto.service;

import java.util.Optional;

/**
 * The weak-PIN rules of spec 18.7 that need the clear PIN, so they run in mw-crypto; mw-auth
 * configures them and passes the inputs ({@code dto/PinPolicyInput}). The PIN stays a {@code byte[]}
 * of 5 ASCII digits and is never turned into a String. The last-PIN rule compares HMACs ({@code
 * PinService}). Runs wrap around (67890, 09876 are runs too).
 */
final class WeakPinPolicy {

  static final String REPEATED_DIGIT = "REPEATED_DIGIT";
  static final String SEQUENCE = "SEQUENCE";
  static final String MSISDN_DIGITS = "MSISDN_DIGITS";
  static final String BIRTH_YEAR = "BIRTH_YEAR";
  static final String LAST_PIN = "LAST_PIN";

  private WeakPinPolicy() {}

  /** The first rule {@code pin} breaks, empty when it is good. */
  static Optional<String> rule(byte[] pin, String msisdn, Integer birthYear) {
    if (steps(pin, 0)) {
      return Optional.of(REPEATED_DIGIT);
    }
    if (steps(pin, 1) || steps(pin, 9)) {
      return Optional.of(SEQUENCE);
    }
    if (msisdn != null && containedIn(pin, msisdn.replaceAll("[^0-9]", ""))) {
      return Optional.of(MSISDN_DIGITS);
    }
    if (birthYear != null && containsYear(pin, Integer.toString(birthYear))) {
      return Optional.of(BIRTH_YEAR);
    }
    return Optional.empty();
  }

  /** Every next digit is the previous one plus {@code step} (mod 10). */
  private static boolean steps(byte[] pin, int step) {
    for (int i = 1; i < pin.length; i++) {
      if ((pin[i - 1] - '0' + step) % 10 != pin[i] - '0') {
        return false;
      }
    }
    return true;
  }

  /** The PIN is a run of consecutive digits of {@code digits}. */
  private static boolean containedIn(byte[] pin, String digits) {
    for (int start = 0; start + pin.length <= digits.length(); start++) {
      boolean same = true;
      for (int i = 0; i < pin.length && same; i++) {
        same = digits.charAt(start + i) == pin[i];
      }
      if (same) {
        return true;
      }
    }
    return false;
  }

  /** The year's digits appear consecutively in the PIN. */
  private static boolean containsYear(byte[] pin, String year) {
    for (int start = 0; start + year.length() <= pin.length; start++) {
      boolean same = true;
      for (int i = 0; i < year.length() && same; i++) {
        same = pin[start + i] == year.charAt(i);
      }
      if (same) {
        return true;
      }
    }
    return false;
  }
}
