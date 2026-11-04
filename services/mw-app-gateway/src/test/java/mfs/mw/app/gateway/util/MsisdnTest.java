package mfs.mw.app.gateway.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Every MSISDN this service accepts is turned into one canonical form ({@code 01XXXXXXXXX}) before
 * it goes anywhere, and every accepted form is masked by {@link MaskingPolicy#scrub} (18.3), so
 * what the validator lets in can never reach a log unmasked.
 */
class MsisdnTest {

  /** The forms {@link Msisdn#PATTERN} accepts (the request validator uses the same pattern). */
  static final List<String> ACCEPTED = List.of("01711000002", "8801711000002", "+8801711000002");

  @Test
  void acceptedFormsBecomeTheCanonicalForm() {
    for (String v : ACCEPTED) {
      assertThat(v).matches(Msisdn.PATTERN);
      assertThat(Msisdn.canonical(v)).as(v).isEqualTo("01711000002");
    }
  }

  @Test
  void otherFormsAreRejected() {
    for (String v :
        List.of("88001711000002", "+88001711000002", "1711000002", "01211000002", "0171100000", "017110000021",
            "0171100000a", " 01711000002", "")) {
      assertThat(v).as(v).doesNotMatch(Msisdn.PATTERN);
      assertThat(Msisdn.canonical(v)).as(v).isNull();
    }
    assertThat(Msisdn.canonical(null)).isNull();
  }

  @Test
  void everyAcceptedFormIsMaskedInFreeText() {
    for (String v : ACCEPTED) {
      String scrubbed = MaskingPolicy.scrub("payee " + v + " paid");
      assertThat(scrubbed).as(v).doesNotContain("1711000002").doesNotContain("11000");
    }
  }
}
