package mfs.mw.notify.service;

import static org.assertj.core.api.Assertions.assertThat;

import mfs.mw.notify.dto.Priority;
import org.junit.jupiter.api.Test;

/** Promotional opt-out never applies to OTP or transactional messages (FRS NOT-09). */
class PromoOptOutTest {

  @Test
  void optOutAppliesToPromoOnly() {
    assertThat(PromoOptOut.blocks(Priority.PROMO, true)).isTrue();
    assertThat(PromoOptOut.blocks(Priority.OTP, true)).isFalse();
    assertThat(PromoOptOut.blocks(Priority.TXN, true)).isFalse();
    assertThat(PromoOptOut.blocks(Priority.INFO, true)).isFalse();
    assertThat(PromoOptOut.blocks(Priority.PROMO, false)).isFalse();
  }
}
