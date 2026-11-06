package mfs.mw.seeder.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TotpTest {

  /** RFC 6238 appendix B, SHA-256 seed, last 6 of the 8 digits given there. */
  private static final String RFC_SEED = "12345678901234567890123456789012";

  @Test
  void matchesTheRfc6238Sha256Vectors() {
    assertThat(Totp.at(RFC_SEED, 59 / 30)).isEqualTo("119246");
    assertThat(Totp.at(RFC_SEED, 1111111109L / 30)).isEqualTo("084774");
    assertThat(Totp.at(RFC_SEED, 2000000000L / 30)).isEqualTo("698825");
  }

  @Test
  void theTimeStepIsThirtySeconds() {
    assertThat(Totp.step(59_999)).isEqualTo(1);
    assertThat(Totp.step(60_000)).isEqualTo(2);
  }
}
