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
  void theSameKeyRuleAppliesToXmlAndKeyValueText() {
    String xml =
        PayloadScrub.scrub(
            "application/xml",
            "<req><userPin>1234</userPin><ns:mpin>5678</ns:mpin><pinBlock><fmt>ISO-0</fmt><v>0412AC</v></pinBlock>"
                + "<shipping>fast</shipping></req>");
    assertThat(xml).doesNotContain("1234", "5678", "ISO-0", "0412AC");
    assertThat(xml).contains("<userPin>****</userPin>", "<pinBlock>****</pinBlock>", "<shipping>fast</shipping>");

    String form =
        PayloadScrub.scrub("text/plain", "mpin=9999&walletPassword=hunter2&otpCode=112233&spinner=on&pin_code=4444");
    assertThat(form).doesNotContain("9999", "hunter2", "112233", "4444").contains("spinner=on", "mpin=****");
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
  void deepNestingFailsClosed() {
    String open = "<a>".repeat(40);
    String close = "</a>".repeat(40);
    String out = PayloadScrub.scrub("application/xml", "<req>" + open + "<pin>2468</pin>" + close + "</req>");

    assertThat(out).doesNotContain("2468");
    String unclosed = PayloadScrub.scrub("application/xml", "<req><pin>1357<a>9</a>");
    assertThat(unclosed).doesNotContain("1357");
  }

  @Test
  void adversarialXmlFinishesFast() {
    String[] inputs = {
      "<pin>".repeat(20_000),
      "<a>".repeat(20_000) + "x",
      "<a b=\"".repeat(20_000),
      "<x>" + "<y>a</x>".repeat(10_000),
      "<" + "a".repeat(60_000),
    };
    for (String in : inputs) {
      long start = System.nanoTime();
      String out = PayloadScrub.scrub("application/xml", in);
      long ms = (System.nanoTime() - start) / 1_000_000;
      assertThat(ms).as("input of %d chars took %d ms", in.length(), ms).isLessThan(1_000);
      assertThat(out).isNotNull();
    }
  }

  @Test
  void anOversizedBodyIsBlankedWhole() {
    String big = "{\"note\":\"" + "x".repeat(PayloadScrub.MAX_SCAN) + "\"}";

    assertThat(PayloadScrub.scrub("application/json", big)).isEqualTo("****");
  }

  @Test
  void aStrayLessThanOrAnUnmatchedQuoteDoesNotStopTheScan() {
    assertThat(PayloadScrub.scrub("text/plain", "a < b <pin>1234</pin>")).doesNotContain("1234");
    assertThat(PayloadScrub.scrub("application/xml", "<a title=\"x <pin>1234</pin>")).doesNotContain("1234");
    assertThat(PayloadScrub.scrub("application/xml", "<a b='x>y'><pin>5678</pin></a>")).doesNotContain("5678");
    assertThat(PayloadScrub.scrub("text/plain", "if (a<b) then <otp>9876</otp>")).doesNotContain("9876");
  }

  @Test
  void cdataCommentsAndDeclarationsAreBlankedWhole() {
    assertThat(PayloadScrub.scrub("application/xml", "<r><![CDATA[<pin>1234</pin>]]></r>")).doesNotContain("1234");
    assertThat(PayloadScrub.scrub("application/xml", "<r><!-- <pin>2345</pin> --></r>")).doesNotContain("2345");
    assertThat(PayloadScrub.scrub("application/xml", "<r><!-- unterminated <pin>3456</pin>")).doesNotContain("3456");
    assertThat(PayloadScrub.scrub("application/xml", "<r><![CDATA[ unterminated 4567")).doesNotContain("4567");
    assertThat(PayloadScrub.scrub("application/xml", "<!DOCTYPE r [<!ENTITY pin \"5678\">]><r/>")).doesNotContain("5678");
    assertThat(PayloadScrub.scrub("application/xml", "<?pin 6789?><r/>")).doesNotContain("6789");
    // an ordinary XML declaration is kept
    assertThat(PayloadScrub.scrub("application/xml", "<?xml version=\"1.0\"?><r>ok</r>"))
        .isEqualTo("<?xml version=\"1.0\"?><r>ok</r>");
  }

  @Test
  void aMismatchedCloseInsideASecretKeepsBlanking() {
    assertThat(PayloadScrub.scrub("application/xml", "<pin></x>1234</pin>")).doesNotContain("1234");
    assertThat(PayloadScrub.scrub("application/xml", "<pin><a></b>2345</a>3456</pin><n>ok</n>"))
        .doesNotContain("2345", "3456")
        .contains("<n>ok</n>");
    assertThat(PayloadScrub.scrub("application/xml", "<ns:pin></pin>4567</ns:pin>")).doesNotContain("4567");
  }

  @Test
  void fuzzedXmlNeverShowsTheSecretMarker() {
    java.util.Random rnd = new java.util.Random(130_3L);
    String marker = "QZXSECRETVALUE";
    String[] secrets = {"pin", "ns:pin", "userPin", "pinBlock", "otp", "walletPassword", "cvv2"};
    String[] mutations = {
      "<", "\"", "'", ">", "<![CDATA[x]]>", "<!-- c -->", "</zz>", "<a>", "</a>", "<!--", "<![CDATA[", "-->",
      "]]>", "<?x y?>", "<!DOCTYPE d>", "<b k=\"v>\">", "<c/>", "a<b", "&lt;", "<pin", "<a b='"
    };
    for (int run = 0; run < 3000; run++) {
      String secret = secrets[rnd.nextInt(secrets.length)];
      java.util.List<String> tokens = new java.util.ArrayList<>();
      int depth = rnd.nextInt(6);
      for (int d = 0; d < depth; d++) {
        tokens.add("<e" + d + ">");
      }
      tokens.add("<" + secret + (rnd.nextBoolean() ? " a=\"1\"" : "") + ">");
      tokens.add(marker);
      tokens.add("</" + secret + ">");
      for (int d = depth - 1; d >= 0; d--) {
        tokens.add("</e" + d + ">");
      }
      int count = rnd.nextInt(5);
      for (int k = 0; k < count; k++) {
        String mutation = mutations[rnd.nextInt(mutations.length)];
        int secretOpen = tokens.indexOf(tokens.stream().filter(t -> t.startsWith("<" + secret)).findFirst().orElseThrow());
        // A lone comment / CDATA opener before the secret element turns its opening tag into comment
        // text: the marker is then no longer in a secret element (by XML), so it goes after it only.
        int at = mutation.equals("<!--") || mutation.equals("<![CDATA[")
            ? secretOpen + 1 + rnd.nextInt(tokens.size() - secretOpen)
            : rnd.nextInt(tokens.size() + 1);
        tokens.add(at, mutation);
      }
      String in = String.join(rnd.nextBoolean() ? "" : " ", tokens);
      String out = PayloadScrub.scrub(rnd.nextBoolean() ? "application/xml" : "text/plain", in);
      assertThat(out).as("input %s", in).doesNotContain(marker);
    }
  }

  @Test
  void nullAndEmptyStayAsTheyAre() {
    assertThat(PayloadScrub.scrub("application/json", null)).isNull();
    assertThat(PayloadScrub.scrub("text/plain", "")).isEmpty();
  }
}
