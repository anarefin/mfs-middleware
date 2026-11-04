package mfs.mw.ussd.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.regex.Pattern;
import mfs.mw.ussd.dto.ValidatorSpec;
import mfs.mw.ussd.util.Msisdn;
import org.springframework.stereotype.Component;

/**
 * INPUT validators (spec 9.2): msisdn, amount, pin, numeric and regex. A valid input comes back
 * in its stored form (canonical MSISDN, amount with two decimals); an invalid one as empty, and
 * the engine prompts again.
 */
@Component
public class InputValidator {

  private static final Pattern AMOUNT = Pattern.compile("[0-9]{1,9}(\\.[0-9]{1,2})?");
  private static final Pattern DIGITS = Pattern.compile("[0-9]+");
  private static final String DEFAULT_PIN = "[0-9]{4}";

  public Optional<String> validate(ValidatorSpec spec, String input, String ownMsisdn) {
    String v = input == null ? "" : input.trim();
    return switch (spec.type()) {
      case ValidatorSpec.MSISDN -> msisdn(spec, v, ownMsisdn);
      case ValidatorSpec.AMOUNT -> amount(spec, v);
      case ValidatorSpec.PIN -> matches(spec.pattern() == null ? DEFAULT_PIN : spec.pattern(), v);
      case ValidatorSpec.NUMERIC -> numeric(spec, v);
      case ValidatorSpec.REGEX -> matches(spec.pattern(), v);
      default -> throw new IllegalArgumentException("unknown validator " + spec.type());
    };
  }

  private static Optional<String> msisdn(ValidatorSpec spec, String v, String own) {
    String c = Msisdn.canonical(v);
    if (c == null || (spec.notSelf() && c.equals(Msisdn.canonical(own)))) {
      return Optional.empty();
    }
    return Optional.of(c);
  }

  private static Optional<String> amount(ValidatorSpec spec, String v) {
    if (!AMOUNT.matcher(v).matches()) {
      return Optional.empty();
    }
    BigDecimal a = new BigDecimal(v).setScale(2, RoundingMode.UNNECESSARY);
    if (a.signum() <= 0
        || (spec.min() != null && a.compareTo(new BigDecimal(spec.min())) < 0)
        || (spec.max() != null && a.compareTo(new BigDecimal(spec.max())) > 0)) {
      return Optional.empty();
    }
    return Optional.of(a.toPlainString());
  }

  private static Optional<String> numeric(ValidatorSpec spec, String v) {
    if (!DIGITS.matcher(v).matches()
        || (spec.min() != null && v.length() < Integer.parseInt(spec.min()))
        || (spec.max() != null && v.length() > Integer.parseInt(spec.max()))) {
      return Optional.empty();
    }
    return Optional.of(v);
  }

  private static Optional<String> matches(String pattern, String v) {
    return Pattern.compile(pattern).matcher(v).matches() ? Optional.of(v) : Optional.empty();
  }
}
