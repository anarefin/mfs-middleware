package mfs.mw.ussd.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Validator of an INPUT node (spec 9.2).
 *
 * @param type {@code msisdn}, {@code amount}, {@code pin}, {@code numeric} or {@code regex}
 * @param pattern the pattern for {@code regex} (and an optional override for {@code pin})
 * @param min amount in taka (amount) or minimum digits (numeric)
 * @param max amount in taka (amount) or maximum digits (numeric)
 * @param notSelf msisdn only: refuse the caller's own number
 * @param error the re-prompt text; the menu's {@code invalid} message when absent
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ValidatorSpec(
    String type, String pattern, String min, String max, boolean notSelf, Text error) {

  public static final String MSISDN = "msisdn";
  public static final String AMOUNT = "amount";
  public static final String PIN = "pin";
  public static final String NUMERIC = "numeric";
  public static final String REGEX = "regex";

  public boolean isPin() {
    return PIN.equals(type);
  }
}
