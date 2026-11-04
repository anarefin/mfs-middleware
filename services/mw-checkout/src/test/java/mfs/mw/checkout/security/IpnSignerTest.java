package mfs.mw.checkout.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The IPN signature merchants verify (contract checkout.v1, "Signed IPN"). */
class IpnSignerTest {

  static final String SECRET = "test-merchant-secret-not-real";
  static final String BODY = "{\"sessionId\":\"s1\",\"status\":\"PAID\"}";

  @Test
  void signsPrefixTimestampAndExactBodyLikeOpenssl() {
    // printf 'upay-checkout-ipn.v1\n1762336800\n{"sessionId":"s1","status":"PAID"}' \
    //   | openssl dgst -sha256 -hmac 'test-merchant-secret-not-real' -hex
    assertThat(IpnSigner.sign(SECRET, 1762336800L, BODY))
        .isEqualTo("c18cd1a828d85727a9282a091fbecd45cdd88f3ec8aa6cbb134da8b90740b17c");
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
