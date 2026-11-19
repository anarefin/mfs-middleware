package mfs.mw.checkout.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Set;
import mfs.mw.checkout.controller.MerchantSessionController;
import mfs.mw.checkout.exception.MwException;
import mfs.mw.checkout.repository.MerchantNonceRepository;
import mfs.mw.checkout.service.RequestReplayGuard;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Replay protection of the merchant's HMAC-signed session request (ticket 99, FRS PLT-008): the
 * signed {@code Date} must be less than 5 minutes away and the signed {@code X-Nonce} unused by
 * that merchant; a stale request or a reused nonce is 401, a fresh one passes.
 */
class HmacReplayTest {

  static final Instant NOW = Instant.parse("2026-11-19T10:00:00Z");

  /** In memory: first use of (merchant, nonce). */
  static final class FakeNonces extends MerchantNonceRepository {
    final Set<String> seen = new HashSet<>();

    FakeNonces() {
      super(null);
    }

    @Override
    public boolean firstUse(String merchantId, String nonce, Instant now, Duration window) {
      return seen.add(merchantId + "|" + nonce);
    }
  }

  final FakeNonces nonces = new FakeNonces();
  final MerchantReplayInterceptor interceptor =
      new MerchantReplayInterceptor(new RequestReplayGuard(nonces, Clock.fixed(NOW, ZoneOffset.UTC)));

  static MockHttpServletRequest request(String merchant, Instant date, String nonce) {
    MockHttpServletRequest r = new MockHttpServletRequest("POST", "/checkout/v1/sessions");
    r.addHeader(MerchantSessionController.MERCHANT_HEADER, merchant);
    if (date != null) {
      r.addHeader("Date", DateTimeFormatter.RFC_1123_DATE_TIME.format(date.atOffset(ZoneOffset.UTC)));
    }
    if (nonce != null) {
      r.addHeader("X-Nonce", nonce);
    }
    return r;
  }

  boolean pass(MockHttpServletRequest r) throws Exception {
    return interceptor.preHandle(r, new MockHttpServletResponse(), new Object());
  }

  static String code(Throwable t) {
    return ((MwException) t).code();
  }

  @Test
  void aFreshSignedRequestPasses() throws Exception {
    assertThat(pass(request("merchant_demo", NOW.minusSeconds(10), "nonce-0001-aaaaaaaaaaaa"))).isTrue();
  }

  @Test
  void aReusedNonceIsRefusedButAnotherMerchantMayUseTheSameValue() throws Exception {
    pass(request("merchant_demo", NOW, "nonce-0002-aaaaaaaaaaaa"));

    assertThatThrownBy(() -> pass(request("merchant_demo", NOW, "nonce-0002-aaaaaaaaaaaa")))
        .satisfies(t -> assertThat(code(t)).isEqualTo("MW-AUTH-006"));
    assertThat(pass(request("merchant_fashion", NOW, "nonce-0002-aaaaaaaaaaaa"))).isTrue();
  }

  @Test
  void aStaleOrFutureDateIsRefused() {
    assertThatThrownBy(() -> pass(request("merchant_demo", NOW.minusSeconds(300), "nonce-0003-aaaaaaaaaaaa")))
        .satisfies(t -> assertThat(code(t)).isEqualTo("MW-AUTH-005"));
    assertThatThrownBy(() -> pass(request("merchant_demo", NOW.plusSeconds(301), "nonce-0004-aaaaaaaaaaaa")))
        .satisfies(t -> assertThat(code(t)).isEqualTo("MW-AUTH-005"));
    assertThat(nonces.seen).as("a stale request burns no nonce").isEmpty();
  }

  @Test
  void aMissingOrMalformedDateOrNonceIsRefused() {
    assertThatThrownBy(() -> pass(request("merchant_demo", null, "nonce-0005-aaaaaaaaaaaa")))
        .satisfies(t -> assertThat(code(t)).isEqualTo("MW-AUTH-005"));
    assertThatThrownBy(() -> pass(request("merchant_demo", NOW, null)))
        .satisfies(t -> assertThat(code(t)).isEqualTo("MW-AUTH-006"));
    assertThatThrownBy(() -> pass(request("merchant_demo", NOW, "short")))
        .satisfies(t -> assertThat(code(t)).isEqualTo("MW-AUTH-006"));
  }

  @Test
  void onlyTheSessionCreateIsChecked() throws Exception {
    MockHttpServletRequest read = new MockHttpServletRequest("GET", "/checkout/v1/sessions/abc");
    assertThat(pass(read)).isTrue();
  }
}
