package mfs.mw.crypto.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** The weak-PIN rules of spec 18.7 that need the clear PIN (the last-PIN rule is in PinServiceTest). */
class WeakPinPolicyTest {

  static String rule(String pin, String msisdn, Integer birthYear) {
    return WeakPinPolicy.rule(pin.getBytes(StandardCharsets.US_ASCII), msisdn, birthYear).orElse(null);
  }

  @ParameterizedTest
  @ValueSource(strings = {"00000", "11111", "99999"})
  void allOneDigitIsRefused(String pin) {
    assertThat(rule(pin, null, null)).isEqualTo("REPEATED_DIGIT");
  }

  @ParameterizedTest
  @ValueSource(strings = {"01234", "12345", "56789", "67890", "90123", "43210", "98765", "09876", "10987"})
  void ascendingAndDescendingRunsAreRefused(String pin) {
    assertThat(rule(pin, null, null)).isEqualTo("SEQUENCE");
  }

  @ParameterizedTest
  @CsvSource({"01712345678, 71234", "01712345678, 17123", "+8801712345678, 80171", "01712345678, 01712"})
  void anyFiveConsecutiveMsisdnDigitsAreRefused(String msisdn, String pin) {
    assertThat(rule(pin, msisdn, null)).isEqualTo("MSISDN_DIGITS");
  }

  @ParameterizedTest
  @ValueSource(strings = {"19905", "71990"})
  void thePinMayNotContainTheBirthYear(String pin) {
    assertThat(rule(pin, null, 1990)).isEqualTo("BIRTH_YEAR");
  }

  @Test
  void aGoodPinPassesEveryRule() {
    assertThat(rule("40973", "01712345678", 1990)).isNull();
    assertThat(rule("13579", null, null)).isNull();
    assertThat(rule("12346", null, null)).isNull();
    assertThat(rule("19915", "01712345678", 1990)).isNull();
  }
}
