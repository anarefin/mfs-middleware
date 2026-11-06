package mfs.mw.checkout.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The IPN signature merchants verify (contract checkout.v1, "Signed IPN"). */
class IpnSignerTest {

  static final String SECRET = "test-merchant-secret-not-real";
  static final String BODY = "{\"sessionId\":\"s1\",\"status\":\"PAID\"}";

  @Test
  void signsPrefixTimestampAndExactBodyLikeOpenssl() {
    // printf 'bits-checkout-ipn.v1\n1762336800\n{"sessionId":"s1","status":"PAID"}' \
    //   | openssl dgst -sha256 -hmac 'test-merchant-secret-not-real' -hex
    assertThat(IpnSigner.sign(SECRET, 1762336800L, BODY))
        .isEqualTo("b4fba6ca92f01b777c9b70651d3c4adf511506a7d1f8a4c7b2322ab52c0003f9");
  }

  @Test
  void aMerchantAcceptsOnlyTheUntouchedBodyAndTimestamp() {
    String sig = IpnSigner.sign(SECRET, 1762336800L, BODY);

    assertThat(IpnSigner.verify(SECRET, "1762336800", BODY, sig)).isTrue();
    assertThat(IpnSigner.verify(SECRET, "1762336801", BODY, sig)).isFalse();
    assertThat(IpnSigner.verify(SECRET, "1762336800", BODY.replace("PAID", "FAILED"), sig)).isFalse();
    assertThat(IpnSigner.verify("another-secret", "1762336800", BODY, sig)).isFalse();
    assertThat(IpnSigner.verify(SECRET, "x", BODY, sig)).isFalse();
    assertThat(IpnSigner.verify(SECRET, "1762336800", BODY, null)).isFalse();
  }

  @Test
  void theIpnSignatureDiffersFromTheRedirectSignatureForTheSameText() {
    assertThat(IpnSigner.sign(SECRET, 1L, "x")).isNotEqualTo(ResultSigner.hmacHex(SECRET, "1\nx"));
  }
}
