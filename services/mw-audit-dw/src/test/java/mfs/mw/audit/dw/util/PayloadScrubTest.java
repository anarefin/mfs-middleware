package mfs.mw.audit.dw.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The defensive scrub of a hop payload on write (ticket 130, FRS AUD-01, CON-04): producers mask
 * before they publish, mw-audit-dw masks again, so a PIN, a card PAN or a clear MSISDN that slipped
 * through never reaches {@code audit.payload}.
 */
class PayloadScrubTest {

  @Test
  void jsonPinLikeFieldsAreBlankedAtAnyDepth() {
    String out =
        PayloadScrub.scrub(
            "application/json",
            """
            {"pin":"1234","auth":{"pinBlock":"0412AC9F33B1","otp":"884213","Password":"hunter2"},
             "items":[{"newPin":"5678"}],"amount":"20.00"}
            """);

    assertThat(out).doesNotContain("1234", "0412AC9F33B1", "884213", "hunter2", "5678");
    assertThat(out).contains("\"pin\":\"****\"", "\"pinBlock\":\"****\"", "\"amount\":\"20.00\"");
  }

  @Test
  void aCardPanIsCutToItsLastFourEvenWithSeparators() {
    String out =
        PayloadScrub.scrub(
            "application/json", "{\"card\":\"4111 1111 1111 1111\",\"pan\":\"5500-0000-0000-0004\",\"note\":\"x\"}");

    assertThat(out).doesNotContain("4111 1111 1111 1111", "5500-0000-0000-0004");
    assertThat(out).contains("****1111", "****0004");
  }

  @Test
  void aClearMsisdnIsMaskedInJsonValuesAndPropertyNames() {
    String out = PayloadScrub.scrub("application/json", "{\"msisdn\":\"01711234567\",\"01811000002\":\"x\"}");

    assertThat(out).doesNotContain("01711234567", "01811000002");
    assertThat(out).contains("0171****567", "0181****002");
  }

  @Test
  void textAndXmlBodiesAreScrubbedToo() {
    String out =
        PayloadScrub.scrub(
            "application/xml",
            "<req><pin>4321</pin><msisdn>+8801711234567</msisdn><card>4111111111111111</card></req>");

    assertThat(out).doesNotContain("4321", "01711234567", "4111111111111111");
    assertThat(out).contains("<pin>****</pin>", "****1111");

    String form = PayloadScrub.scrub("application/x-www-form-urlencoded", "msisdn=01711234567&pin=9999&otp=123456");
    assertThat(form).doesNotContain("01711234567", "9999", "123456").contains("pin=****", "otp=****");
  }

  @Test
  void aBrokenJsonBodyIsScrubbedAsText() {
    String out = PayloadScrub.scrub("application/json", "{\"pin\":\"2468\",\"msisdn\":\"01711234567\"");

    assertThat(out).doesNotContain("2468", "01711234567");
  }

  @Test
  void nullAndEmptyStayAsTheyAre() {
    assertThat(PayloadScrub.scrub("application/json", null)).isNull();
    assertThat(PayloadScrub.scrub("text/plain", "")).isEmpty();
  }
}
