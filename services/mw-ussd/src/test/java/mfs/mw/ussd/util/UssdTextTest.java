package mfs.mw.ussd.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Money on a USSD screen (ADR D26): poisha as taka with grouping and two decimals. */
class UssdTextTest {

  @Test
  void poishaIsShownAsTakaWithThousandsAndTwoDecimals() {
    assertThat(UssdText.taka(1_200_000L)).isEqualTo("12,000.00");
    assertThat(UssdText.taka(0L)).isEqualTo("0.00");
    assertThat(UssdText.taka(5L)).isEqualTo("0.05");
    assertThat(UssdText.taka(99_999L)).isEqualTo("999.99");
    assertThat(UssdText.taka(123_456_789_01L)).isEqualTo("123,456,789.01");
  }

  @Test
  void aNegativeAmountKeepsItsSign() {
    assertThat(UssdText.taka(-150L)).isEqualTo("-1.50");
  }

  @Test
  void theDigitsAreAsciiWhateverTheDefaultLocale() {
    java.util.Locale before = java.util.Locale.getDefault();
    try {
      java.util.Locale.setDefault(java.util.Locale.forLanguageTag("bn-BD-u-nu-beng"));
      assertThat(UssdText.taka(1_200_000L)).isEqualTo("12,000.00");
    } finally {
      java.util.Locale.setDefault(before);
    }
  }
}
