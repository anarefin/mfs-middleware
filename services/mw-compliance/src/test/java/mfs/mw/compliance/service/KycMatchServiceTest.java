package mfs.mw.compliance.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import mfs.mw.compliance.config.ComplianceProperties;
import mfs.mw.compliance.dto.EcPerson;
import mfs.mw.compliance.dto.KycLevel;
import mfs.mw.compliance.dto.MatchResult;
import org.junit.jupiter.api.Test;

/**
 * The match rule table (ticket 124, product decision in 124b): exact DOB and name similarity >= threshold FULL; DOB
 * only is held for manual review (no level); another DOB MISMATCHED.
 */
class KycMatchServiceTest {

  private static final LocalDate DOB = LocalDate.of(1990, 5, 17);

  private final KycMatchService match = new KycMatchService(ComplianceProperties.defaults());

  private static EcPerson ec(String en, String bn, LocalDate dob) {
    return new EcPerson("1990123456789", dob, en, bn, null, null, null);
  }

  @Test
  void englishNameDifferingOnlyInCaseAndPunctuationIsFull() {
    MatchResult r = match.match(DOB, "md. rahim  uddin", null, ec("MD RAHIM UDDIN", "মোঃ রহিম উদ্দিন", DOB));
    assertThat(r.level()).isEqualTo(KycLevel.FULL);
    assertThat(r.matched()).isTrue();
    assertThat(r.score()).isEqualTo(1.0);
  }

  @Test
  void banglaNameIsFull() {
    MatchResult r = match.match(DOB, null, "মোঃ রহিম উদ্দিন", ec("MD RAHIM UDDIN", "মোঃ রহিম উদ্দিন", DOB));
    assertThat(r.level()).isEqualTo(KycLevel.FULL);
  }

  @Test
  void banglaPrecomposedAndDecomposedLettersAreTheSameName() {
    // U+09DF (য়) against U+09AF U+09BC (য + nukta), and a zero-width joiner.
    MatchResult r = match.match(DOB, null, "সৈয়দ কবির", ec("SYED KABIR", "সৈয়‍দ কবির", DOB));
    assertThat(r.level()).isEqualTo(KycLevel.FULL);
  }

  @Test
  void smallSpellingDifferenceAndSwappedOrderStayAboveTheThreshold() {
    assertThat(match.match(DOB, "Rahim Udin", null, ec("RAHIM UDDIN", null, DOB)).level()).isEqualTo(KycLevel.FULL);
    assertThat(match.match(DOB, "Uddin Rahim", null, ec("RAHIM UDDIN", null, DOB)).level()).isEqualTo(KycLevel.FULL);
  }

  @Test
  void theBetterOfTheEnglishAndBanglaScoresCounts() {
    MatchResult r = match.match(DOB, "Someone Else", "মোঃ রহিম উদ্দিন", ec("MD RAHIM UDDIN", "মোঃ রহিম উদ্দিন", DOB));
    assertThat(r.level()).isEqualTo(KycLevel.FULL);
  }

  @Test
  void sameDobButAnotherNameIsHeldForManualReviewWithoutALevel() {
    MatchResult r = match.match(DOB, "Karim Hossain", "করিম হোসেন", ec("RAHIM UDDIN", "রহিম উদ্দিন", DOB));
    assertThat(r.level()).isNull();
    assertThat(r.matched()).isFalse();
    assertThat(r.manualReview()).isTrue();
    assertThat(r.reason()).isEqualTo("NAME_MISMATCH");
    assertThat(r.score()).isLessThan(0.85);
  }

  @Test
  void anotherDobIsMismatchedWhateverTheName() {
    MatchResult r = match.match(DOB, "Rahim Uddin", null, ec("RAHIM UDDIN", null, DOB.plusDays(1)));
    assertThat(r.level()).isNull();
    assertThat(r.matched()).isFalse();
    assertThat(r.reason()).isEqualTo("DOB_MISMATCH");
    assertThat(r.manualReview()).isFalse();
  }

  @Test
  void noComparableNameIsHeldForManualReviewWhenTheDobMatches() {
    MatchResult r = match.match(DOB, null, "রহিম", ec("RAHIM", null, DOB));
    assertThat(r.level()).isNull();
    assertThat(r.manualReview()).isTrue();
  }
}
