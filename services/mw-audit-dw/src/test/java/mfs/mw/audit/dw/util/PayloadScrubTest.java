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
  void aBrokenJsonBodyIsScrubbedAsText() {
    String out = PayloadScrub.scrub("application/json", "{\"pin\":\"2468\",\"msisdn\":\"01711234567\"");

    assertThat(out).doesNotContain("2468", "01711234567");
  }

  @Test
  void secretWordsAnywhereInAJsonKeyAreBlanked() {
    String out =
        PayloadScrub.scrub(
            "application/json",
            """
            {"mpin":"1111","userPin":"2222","pin_code":"3333","walletPassword":"s3cret-pw","otpCode":"444444",
             "PINBlock":"0412AC","user-passcode":"5555","clientSecret":"abc-xyz","cvv2":"987","card_cvc":"654",
             "txnPin1":"6666","encrypted_pin_cipher":"QUJD"}
            """);

    assertThat(out).doesNotContain("1111", "2222", "3333", "s3cret-pw", "444444", "0412AC", "5555", "abc-xyz",
        "987", "654", "6666", "QUJD");
    assertThat(out).contains("\"mpin\":\"****\"", "\"userPin\":\"****\"", "\"otpCode\":\"****\"");
  }

  @Test
  void aSecretKeyWithAnObjectOrArrayValueIsBlankedWhole() {
    String out =
        PayloadScrub.scrub(
            "application/json",
            "{\"pin\":{\"block\":\"0412AC9F\",\"format\":\"ISO-0\"},\"otp\":[\"123456\",\"654321\"],\"amount\":\"5.00\"}");

    assertThat(out).doesNotContain("0412AC9F", "ISO-0", "123456", "654321");
    assertThat(out).contains("\"pin\":\"****\"", "\"otp\":\"****\"", "\"amount\":\"5.00\"");
  }

  @Test
  void lookAlikeKeysThatAreNotSecretsAreKept() {
    String out =
        PayloadScrub.scrub(
            "application/json",
            "{\"spinner\":\"on\",\"shipping\":\"fast\",\"opinion\":\"good\",\"options\":\"a\",\"topping\":\"cheese\"}");

    assertThat(out).contains("\"spinner\":\"on\"", "\"shipping\":\"fast\"", "\"opinion\":\"good\"",
        "\"options\":\"a\"", "\"topping\":\"cheese\"");
  }

  @Test
  void compoundLowerCaseKeysAreSecretToo() {
    for (String key :
        java.util.List.of(
            "pincipher", "pinciphertext", "newpin", "encryptedpin", "userpassword", "walletpin", "txnpin",
            "oldpasscode", "currentotp", "mpinblock", "otpcode", "NEWPIN", "encrypted_pinciphertext")) {
      assertThat(PayloadScrub.isSecretKey(key)).as(key).isTrue();
      String out = PayloadScrub.scrub("application/json", "{\"" + key + "\":\"S3CR3TVALUE\"}");
      assertThat(out).as(key).doesNotContain("S3CR3TVALUE");
    }
    for (String key : java.util.List.of("spinner", "shipping", "opinion", "options", "topping", "pinnacle", "happiness")) {
      assertThat(PayloadScrub.isSecretKey(key)).as(key).isFalse();
    }
  }

  @Test
  void anOversizedBodyIsBlankedWhole() {
    String big = "{\"note\":\"" + "x".repeat(PayloadScrub.MAX_SCAN) + "\"}";

    assertThat(PayloadScrub.scrub("application/json", big)).isEqualTo("****");
  }

  @Test
  void keyValueTextBlanksSecretKeysAndMasksTheRest() {
    String form =
        PayloadScrub.scrub(
            "application/x-www-form-urlencoded",
            "msisdn=01711234567&pin=9999&otpCode=112233&walletPassword=hunter2&spinner=on");

    assertThat(form).doesNotContain("01711234567", "9999", "112233", "hunter2");
    assertThat(form).contains("pin=****", "otpCode=****", "spinner=on");
  }

  @Test
  void markupWithASecretAnywhereIsStoredAsStarsWhole() {
    assertThat(PayloadScrub.scrub("application/xml", "<pin a=\"<\">1234</pin>")).isEqualTo("****");
    assertThat(PayloadScrub.scrub("application/xml", "<req><p.i-n:x>1234</p.i-n:x></req>")).isEqualTo("****");
    assertThat(PayloadScrub.scrub("application/xml", "<r><![CDATA[<pin>1234</pin>]]></r>")).isEqualTo("****");
    assertThat(PayloadScrub.scrub("application/xml", "<r><!-- <otp>1234</otp> --></r>")).isEqualTo("****");
    assertThat(PayloadScrub.scrub("text/plain", "a < b and the PASSWORD is hunter2")).isEqualTo("****");
    assertThat(PayloadScrub.scrub("application/soap+xml", "<Envelope><Body><Auth><CVV2>987</CVV2></Auth></Body></Envelope>"))
        .isEqualTo("****");
    assertThat(PayloadScrub.scrub("application/xml", "<r><newPIN>1</newPIN></r>")).isEqualTo("****");
  }

  @Test
  void aLookAlikeSuchAsSpinnerIsBlankedTooBecauseMarkupFailsClosed() {
    assertThat(PayloadScrub.scrub("application/xml", "<spinner>on</spinner>")).isEqualTo("****");
    assertThat(PayloadScrub.scrub("application/xml", "<shipping>fast</shipping>")).isEqualTo("****");
  }

  @Test
  void markupWithoutSecretsIsKeptAndStillMasked() {
    String in = "<?xml version=\"1.0\"?><req><amount>20.00</amount><ref>OCS-1</ref></req>";
    assertThat(PayloadScrub.scrub("application/xml", in)).isEqualTo(in);

    String out = PayloadScrub.scrub("application/xml", "<req><msisdn>+8801711234567</msisdn><card>4111111111111111</card></req>");
    assertThat(out).doesNotContain("01711234567", "4111111111111111").contains("****1111", "<card>");
  }

  @Test
  void aLargeMarkupBodyIsCheckedFast() {
    String[] inputs = {"<a>".repeat(20_000) + "x", "<a b=\"".repeat(20_000), "<" + "a".repeat(60_000)};
    for (String in : inputs) {
      long start = System.nanoTime();
      PayloadScrub.scrub("application/xml", in);
      assertThat((System.nanoTime() - start) / 1_000_000).as("%d chars", in.length()).isLessThan(1_000);
    }
  }

  @Test
  void nullAndEmptyStayAsTheyAre() {
    assertThat(PayloadScrub.scrub("application/json", null)).isNull();
    assertThat(PayloadScrub.scrub("text/plain", "")).isEmpty();
  }
}
