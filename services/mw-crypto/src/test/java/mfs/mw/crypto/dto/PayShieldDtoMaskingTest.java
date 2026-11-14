package mfs.mw.crypto.dto;

import static org.assertj.core.api.Assertions.assertThat;

import mfs.mw.crypto.client.PinBlockFormat;
import org.junit.jupiter.api.Test;

/** The payShield DTOs (ticket 64) never print a PIN block or PVV, and the account only as its last 4. */
class PayShieldDtoMaskingTest {

  static final String BLOCK = "0123456789ABCDEF";
  static final String ACCOUNT = "4000001234562000";

  @Test
  void requestsAndAnswersAreMasked() {
    PinBlockInput in = new PinBlockInput("tpk", PinBlockFormat.ISO0, BLOCK);
    String translate = new TranslatePinBlockRequest(in, new TranslatePinBlockRequest.Target("zpk", PinBlockFormat.ISO0),
        ACCOUNT).toString();
    String pvv = new PvvRequest("pvk", 1, in, ACCOUNT, "4821").toString();
    String out = new TranslatedPinBlock("FEDCBA9876543210", PinBlockFormat.ISO0, "zpk.v1").toString();
    String gen = PvvView.generated("4821", "pvk.v1", 1).toString();
    for (String s : new String[] {translate, pvv, out, gen}) {
      assertThat(s).doesNotContain(BLOCK).doesNotContain("FEDCBA9876543210").doesNotContain("4821")
          .doesNotContain(ACCOUNT).doesNotContain("00012345");
    }
    assertThat(translate).contains("2000").contains("tpk");
  }
}
