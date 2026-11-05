package mfs.mw.simulators.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import mfs.mw.simulators.dto.SmscDlrState;
import mfs.mw.simulators.dto.SmscMessage;
import mfs.mw.simulators.exception.SmscRefusalException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * SMSC simulator (spec 22.2, ticket 24): decodes GSM-7 and UCS-2, reassembles concatenated parts
 * by their UDH, and picks the DLR or refusal from the destination's magic suffix.
 */
class SmscServiceTest {

  private SmscService smsc;

  @BeforeEach
  void setUp() {
    smsc = new SmscService(100);
  }

  static byte[] udh(int ref, int total, int seq, byte[] body) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(new byte[] {0x05, 0x00, 0x03, (byte) ref, (byte) total, (byte) seq});
    out.writeBytes(body);
    return out.toByteArray();
  }

  @Test
  void aSingleGsm7MessageIsDecodedIncludingGsmAndExtensionCharacters() throws Exception {
    byte[] gsm = {'T', 'k', ' ', 0x00, 0x01, 0x1B, 0x65, ' ', 'o', 'k'};
    SmscService.Submission s = smsc.submit("01711000001", "upay", (byte) 0, (byte) 0, gsm);

    assertThat(s.dlrState()).isEqualTo(SmscDlrState.DELIVRD);
    SmscMessage m = smsc.messages("01711000001").getFirst();
    assertThat(m.text()).isEqualTo("Tk @£€ ok");
    assertThat(m.encoding()).isEqualTo("GSM7");
    assertThat(m.segments()).isEqualTo(1);
    assertThat(m.source()).isEqualTo("upay");
    assertThat(m.messageIds()).containsExactly(s.messageId());
  }

  @Test
  void concatenatedUcs2PartsAreReassembledInSequenceOrder() throws Exception {
    byte[] a = "আপনি ".getBytes(StandardCharsets.UTF_16BE);
    byte[] b = "পাঠিয়েছেন".getBytes(StandardCharsets.UTF_16BE);
    SmscService.Submission second = smsc.submit("01711000001", "upay", (byte) 8, (byte) 0x40, udh(7, 2, 2, b));

    assertThat(smsc.messages("01711000001")).isEmpty();

    SmscService.Submission first = smsc.submit("01711000001", "upay", (byte) 8, (byte) 0x40, udh(7, 2, 1, a));

    SmscMessage m = smsc.messages("01711000001").getFirst();
    assertThat(m.text()).isEqualTo("আপনি পাঠিয়েছেন");
    assertThat(m.encoding()).isEqualTo("UCS2");
    assertThat(m.segments()).isEqualTo(2);
    assertThat(m.messageIds()).containsExactly(first.messageId(), second.messageId());
  }

  @Test
  void magicSuffixesChooseTheDlrOrARefusal() throws Exception {
    byte[] text = "hi".getBytes(StandardCharsets.US_ASCII);
    assertThat(smsc.submit("01711000999", "upay", (byte) 0, (byte) 0, text).dlrState())
        .isEqualTo(SmscDlrState.UNDELIV);
    assertThat(smsc.submit("01711000998", "upay", (byte) 0, (byte) 0, text).dlrState())
        .isEqualTo(SmscDlrState.EXPIRED);
    assertThat(smsc.submit("01711000995", "upay", (byte) 0, (byte) 0, text).dlrState()).isNull();
    assertThatThrownBy(() -> smsc.submit("01711000996", "upay", (byte) 0, (byte) 0, text))
        .isInstanceOfSatisfying(SmscRefusalException.class, e -> assertThat(e.commandStatus()).isEqualTo(0x0B));
  }

  @Test
  void suffix997IsThrottledTwiceThenAccepted() throws Exception {
    byte[] text = "hi".getBytes(StandardCharsets.US_ASCII);
    for (int i = 0; i < 2; i++) {
      assertThatThrownBy(() -> smsc.submit("01711000997", "upay", (byte) 0, (byte) 0, text))
          .isInstanceOfSatisfying(SmscRefusalException.class, e -> assertThat(e.commandStatus()).isEqualTo(0x58));
    }
    assertThat(smsc.submit("01711000997", "upay", (byte) 0, (byte) 0, text).dlrState())
        .isEqualTo(SmscDlrState.DELIVRD);
    assertThat(smsc.messages("01711000997")).hasSize(1);
  }

  @Test
  void theDlrStateIsRecordedAndResetForgetsEverything() throws Exception {
    SmscService.Submission s = smsc.submit("01711000999", "upay", (byte) 0, (byte) 0, new byte[] {'x'});
    smsc.dlrSent(s.messageId(), SmscDlrState.UNDELIV);

    assertThat(smsc.messages(null).getFirst().dlrStatus()).isEqualTo("UNDELIV");

    smsc.reset();
    assertThat(smsc.messages(null)).isEmpty();
  }
}
