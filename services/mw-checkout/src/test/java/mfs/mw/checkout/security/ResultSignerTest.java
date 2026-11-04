package mfs.mw.checkout.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ResultSignerTest {

  static final String SECRET = "test-merchant-secret-not-real";

  static final ResultSigner.Result PAID =
      new ResultSigner.Result(
          "019a0000-0000-7000-8000-000000000001", "ORD-1", "PAID",
          "019a0000-0000-7000-8000-000000000002", 2500, "BDT", null, 1793700000L);

  @Test
  void signsTheCanonicalResultWithHmacSha256Hex() {
    // expected value computed independently with openssl dgst -sha256 -hmac (see the contract)
    assertThat(ResultSigner.sign(SECRET, PAID))
        .isEqualTo("db450e80c1add5a10490587ab214973a215cc521f2fd708791ad394baefc2c24");
  }

  @Test
  void anyChangedFieldChangesTheSignature() {
    ResultSigner.Result other =
        new ResultSigner.Result(PAID.sessionId(), PAID.orderId(), "FAILED", PAID.txnId(),
            PAID.amountMinor(), PAID.currency(), null, PAID.ts());
    assertThat(ResultSigner.sign(SECRET, other)).isNotEqualTo(ResultSigner.sign(SECRET, PAID));
    assertThat(ResultSigner.sign("another-secret", PAID)).isNotEqualTo(ResultSigner.sign(SECRET, PAID));
  }

  @Test
  void theRedirectUrlCarriesEveryFieldAndTheSignatureAndKeepsTheMerchantsQuery() {
    URI url = ResultSigner.redirectUrl("https://shop.example.test/return?cart=7", SECRET, PAID);

    assertThat(url.getHost()).isEqualTo("shop.example.test");
    Map<String, String> q = query(url);
    assertThat(q)
        .containsEntry("cart", "7")
        .containsEntry("sessionId", PAID.sessionId())
        .containsEntry("orderId", "ORD-1")
        .containsEntry("status", "PAID")
        .containsEntry("txnId", PAID.txnId())
        .containsEntry("amount", "2500")
        .containsEntry("currency", "BDT")
        .containsEntry("ts", "1793700000")
        .containsEntry("signature", "db450e80c1add5a10490587ab214973a215cc521f2fd708791ad394baefc2c24")
        .doesNotContainKey("errorCode");
    assertThat(ResultSigner.verify(SECRET, q)).isTrue();
  }

  @Test
  void verifyRefusesATamperedOrUnsignedQuery() {
    Map<String, String> q = query(ResultSigner.redirectUrl("https://shop.example.test/return", SECRET, PAID));
    Map<String, String> tampered = new LinkedHashMap<>(q);
    tampered.put("amount", "1");
    Map<String, String> unsigned = new LinkedHashMap<>(q);
    unsigned.remove("signature");

    assertThat(ResultSigner.verify(SECRET, tampered)).isFalse();
    assertThat(ResultSigner.verify(SECRET, unsigned)).isFalse();
    assertThat(ResultSigner.verify("another-secret", q)).isFalse();
  }

  @Test
  void aFailedResultNamesItsErrorCode() {
    ResultSigner.Result failed =
        new ResultSigner.Result(PAID.sessionId(), PAID.orderId(), "FAILED", PAID.txnId(), 2500, "BDT",
            "MW-CORE-INSUFFICIENT_BALANCE", PAID.ts());
    Map<String, String> q = query(ResultSigner.redirectUrl("https://shop.example.test/fail", SECRET, failed));

    assertThat(q).containsEntry("errorCode", "MW-CORE-INSUFFICIENT_BALANCE");
    assertThat(ResultSigner.verify(SECRET, q)).isTrue();
  }

  static Map<String, String> query(URI url) {
    Map<String, String> out = new LinkedHashMap<>();
    for (String pair : url.getRawQuery().split("&")) {
      int eq = pair.indexOf('=');
      out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
          URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
    }
    return out;
  }
}
