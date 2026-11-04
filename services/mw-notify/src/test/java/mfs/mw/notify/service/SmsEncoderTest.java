package mfs.mw.notify.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * Encoding choice and segmentation (spec 10.2): GSM-7 for English, UCS-2 for Bangla, long SMS
 * split with a concatenation UDH. Expected sizes are the GSM 03.38 / 03.40 limits: 160 septets or
 * 70 UCS-2 characters in one SMS, 153 and 67 per part once a 6-byte UDH is needed.
 */
class SmsEncoderTest {

  private static final int REF = 0x2A;

  @Test
  void plainEnglishIsGsm7InOneSegmentWithoutUdh() {
    EncodedSms sms = SmsEncoder.encode("You sent Tk 500.00 to 0181****002.", REF);

    assertThat(sms.encoding()).isEqualTo(SmsEncoding.GSM7);
    assertThat(sms.dataCoding()).isEqualTo((byte) 0x00);
    assertThat(sms.segments()).hasSize(1);
    assertThat(sms.udhi()).isFalse();
    // GSM 03.38 maps ASCII letters, digits and '*' to the same code points.
    assertThat(sms.segments().getFirst())
        .isEqualTo("You sent Tk 500.00 to 0181****002.".getBytes(StandardCharsets.US_ASCII));
  }

  @Test
  void gsmCharactersOutsideAsciiUseTheGsmTableAndExtensionCharactersTakeTwoSeptets() {
    EncodedSms sms = SmsEncoder.encode("@£€{", REF);

    assertThat(sms.encoding()).isEqualTo(SmsEncoding.GSM7);
    // '@' = 0x00, '£' = 0x01, '€' = ESC 0x65, '{' = ESC 0x28
    assertThat(sms.segments().getFirst()).containsExactly(0x00, 0x01, 0x1B, 0x65, 0x1B, 0x28);
  }

  @Test
  void banglaIsUcs2BigEndian() {
    String text = "আপনি ৳৫০০ পাঠিয়েছেন";
    EncodedSms sms = SmsEncoder.encode(text, REF);

    assertThat(sms.encoding()).isEqualTo(SmsEncoding.UCS2);
    assertThat(sms.dataCoding()).isEqualTo((byte) 0x08);
    assertThat(sms.segments()).hasSize(1);
    assertThat(sms.segments().getFirst()).isEqualTo(text.getBytes(StandardCharsets.UTF_16BE));
  }

  @Test
  void anyCharacterOutsideGsm7SwitchesTheWholeMessageToUcs2() {
    EncodedSms sms = SmsEncoder.encode("Paid Tk 100 – thanks", REF); // en dash

    assertThat(sms.encoding()).isEqualTo(SmsEncoding.UCS2);
  }

  @Test
  void gsm7SegmentLimitsAre160Then153() {
    assertThat(SmsEncoder.encode("a".repeat(160), REF).segments()).hasSize(1);
    assertThat(SmsEncoder.encode("a".repeat(161), REF).segments()).hasSize(2);
    assertThat(SmsEncoder.encode("a".repeat(306), REF).segments()).hasSize(2);
    assertThat(SmsEncoder.encode("a".repeat(307), REF).segments()).hasSize(3);
  }

  @Test
  void extensionCharactersCountTwiceTowardsTheLimit() {
    assertThat(SmsEncoder.encode("€".repeat(80), REF).segments()).hasSize(1);
    assertThat(SmsEncoder.encode("€".repeat(81), REF).segments()).hasSize(2);
  }

  @Test
  void ucs2SegmentLimitsAre70Then67() {
    assertThat(SmsEncoder.encode("অ".repeat(70), REF).segments()).hasSize(1);
    assertThat(SmsEncoder.encode("অ".repeat(71), REF).segments()).hasSize(2);
    assertThat(SmsEncoder.encode("অ".repeat(134), REF).segments()).hasSize(2);
    assertThat(SmsEncoder.encode("অ".repeat(135), REF).segments()).hasSize(3);
  }

  @Test
  void longMessagePartsCarryAConcatenationUdhWithReferenceTotalAndSequence() {
    EncodedSms sms = SmsEncoder.encode("b".repeat(200), REF);

    assertThat(sms.udhi()).isTrue();
    assertThat(sms.segments()).hasSize(2);
    byte[] first = sms.segments().get(0);
    byte[] second = sms.segments().get(1);
    assertThat(Arrays.copyOf(first, 6)).containsExactly(0x05, 0x00, 0x03, REF, 2, 1);
    assertThat(Arrays.copyOf(second, 6)).containsExactly(0x05, 0x00, 0x03, REF, 2, 2);
    assertThat(first).hasSize(6 + 153);
    assertThat(second).hasSize(6 + 47);
  }

  @Test
  void anEscapePairIsNeverSplitAcrossParts() {
    // 152 plain septets, then '€' (2 septets) would straddle the 153-septet boundary.
    EncodedSms sms = SmsEncoder.encode("a".repeat(152) + "€" + "a".repeat(10), REF);

    byte[] first = sms.segments().get(0);
    assertThat(first).hasSize(6 + 152);
    assertThat(sms.segments().get(1)[6]).isEqualTo((byte) 0x1B);
  }

  @Test
  void ucs2PartsNeverSplitASurrogatePair() {
    String text = "অ".repeat(66) + "😀" + "অ".repeat(5);
    EncodedSms sms = SmsEncoder.encode(text, REF);

    assertThat(sms.segments()).hasSize(2);
    assertThat(sms.segments().get(0)).hasSize(6 + 66 * 2);
  }

  @Test
  void segmentCountIsReportedWithoutEncoding() {
    assertThat(SmsEncoder.segmentCount("a".repeat(161))).isEqualTo(2);
    assertThat(SmsEncoder.segmentCount("অ".repeat(70))).isEqualTo(1);
  }
}
