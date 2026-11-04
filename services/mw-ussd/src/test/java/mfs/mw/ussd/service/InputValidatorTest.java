package mfs.mw.ussd.service;

import static org.assertj.core.api.Assertions.assertThat;

import mfs.mw.ussd.dto.ValidatorSpec;
import org.junit.jupiter.api.Test;

class InputValidatorTest {

  private final InputValidator validator = new InputValidator();

  private static ValidatorSpec spec(String type, String pattern, String min, String max, boolean notSelf) {
    return new ValidatorSpec(type, pattern, min, max, notSelf, null);
  }

  @Test
  void msisdnAcceptsLocalAndInternationalFormsAndGivesTheCanonicalOne() {
    ValidatorSpec s = spec("msisdn", null, null, null, false);
    assertThat(validator.validate(s, "01812345678", "01712345678")).contains("01812345678");
    assertThat(validator.validate(s, "8801812345678", "01712345678")).contains("01812345678");
    assertThat(validator.validate(s, " +8801812345678 ", "01712345678")).contains("01812345678");
  }

  @Test
  void msisdnRefusesBadNumbersAndOwnNumberWhenNotSelf() {
    ValidatorSpec s = spec("msisdn", null, null, null, true);
    assertThat(validator.validate(s, "0121234567", "01712345678")).isEmpty();
    assertThat(validator.validate(s, "abc", "01712345678")).isEmpty();
    assertThat(validator.validate(s, "01212345678", "01712345678")).isEmpty();
    assertThat(validator.validate(s, "8801712345678", "01712345678")).isEmpty();
  }

  @Test
  void amountIsTakaWithAtMostTwoDecimalsWithinTheBounds() {
    ValidatorSpec s = spec("amount", null, "10", "25000", false);
    assertThat(validator.validate(s, "500", "x")).contains("500.00");
    assertThat(validator.validate(s, "10.5", "x")).contains("10.50");
    assertThat(validator.validate(s, "25000", "x")).contains("25000.00");
    assertThat(validator.validate(s, "9.99", "x")).isEmpty();
    assertThat(validator.validate(s, "25000.01", "x")).isEmpty();
    assertThat(validator.validate(s, "1.234", "x")).isEmpty();
    assertThat(validator.validate(s, "-5", "x")).isEmpty();
    assertThat(validator.validate(s, "5O0", "x")).isEmpty();
    assertThat(validator.validate(s, "", "x")).isEmpty();
  }

  @Test
  void pinIsFourDigitsUnlessTheMenuSaysOtherwise() {
    assertThat(validator.validate(spec("pin", null, null, null, false), "4826", "x")).contains("4826");
    assertThat(validator.validate(spec("pin", null, null, null, false), "482", "x")).isEmpty();
    assertThat(validator.validate(spec("pin", null, null, null, false), "48a6", "x")).isEmpty();
    assertThat(validator.validate(spec("pin", "[0-9]{6}", null, null, false), "482611", "x")).contains("482611");
  }

  @Test
  void numericChecksDigitsAndLength() {
    ValidatorSpec s = spec("numeric", null, "6", "8", false);
    assertThat(validator.validate(s, "123456", "x")).contains("123456");
    assertThat(validator.validate(s, "12345", "x")).isEmpty();
    assertThat(validator.validate(s, "123456789", "x")).isEmpty();
    assertThat(validator.validate(s, "12a456", "x")).isEmpty();
  }

  @Test
  void regexMustMatchTheWholeInput() {
    ValidatorSpec s = spec("regex", "[A-Z]{2}[0-9]{3}", null, null, false);
    assertThat(validator.validate(s, "AB123", "x")).contains("AB123");
    assertThat(validator.validate(s, "AB1234", "x")).isEmpty();
  }
}
