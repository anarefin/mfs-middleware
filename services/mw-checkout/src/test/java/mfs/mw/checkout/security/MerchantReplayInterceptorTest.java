package mfs.mw.checkout.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Ticket 116: the refund POST is replay-checked like the session create; reads are not. */
class MerchantReplayInterceptorTest {

  @Test
  void sessionCreateAndRefundCreateAreGuarded() {
    assertThat(MerchantReplayInterceptor.guarded("/checkout/v1/sessions")).isTrue();
    assertThat(MerchantReplayInterceptor.guarded("/checkout/v1/payments/019a0000-0000-7000-8000-000000000001/refunds"))
        .isTrue();
    assertThat(MerchantReplayInterceptor.guarded("/checkout/v1/payments")).isFalse();
    assertThat(MerchantReplayInterceptor.guarded("/checkout/v1/payments/a/b/refunds")).isFalse();
    assertThat(MerchantReplayInterceptor.guarded("/checkout/v1/sessions/019a0000")).isFalse();
  }
}
