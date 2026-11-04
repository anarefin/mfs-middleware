package mfs.mw.checkout.security;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signs the checkout result the browser carries back to the merchant (spec 9.3 step 3). The
 * merchant verifies it with its secret, so a customer cannot turn a failed payment into a paid
 * one by editing the URL.
 *
 * <p>Signature: lower-case hex HMAC-SHA256 with the merchant's secret over the lines
 *
 * <pre>
 * upay-checkout-result.v1
 * sessionId
 * orderId
 * status
 * txnId        (empty when there is none)
 * amount       (minor units)
 * currency
 * errorCode    (empty when there is none)
 * ts           (epoch seconds when the URL was made)
 * </pre>
 *
 * joined with {@code \n} (no trailing newline). The first line separates this message from any
 * other the same secret signs (the IPN of ticket 32 signs its body with its own prefix). The
 * contract {@code contracts/api/mw-checkout/checkout.v1.openapi.yaml} describes it for merchants.
 */
public final class ResultSigner {

  public static final String PREFIX = "upay-checkout-result.v1";

  /** The fields of a signed result, as query parameters. */
  public record Result(
      String sessionId,
      String orderId,
      String status,
      String txnId,
      long amountMinor,
      String currency,
      String errorCode,
      long ts) {}

  private ResultSigner() {}

  public static String sign(String secret, Result r) {
    return hmacHex(secret, canonical(r));
  }

  /** {@code base} (a merchant URL, its own query kept) with the result and signature appended. */
  public static URI redirectUrl(String base, String secret, Result r) {
    Map<String, String> q = new LinkedHashMap<>();
    q.put("sessionId", r.sessionId());
    q.put("orderId", r.orderId());
    q.put("status", r.status());
    if (r.txnId() != null) {
      q.put("txnId", r.txnId());
    }
    q.put("amount", Long.toString(r.amountMinor()));
    q.put("currency", r.currency());
    if (r.errorCode() != null) {
      q.put("errorCode", r.errorCode());
    }
    q.put("ts", Long.toString(r.ts()));
    q.put("signature", sign(secret, r));
    StringJoiner query = new StringJoiner("&");
    q.forEach((k, v) -> query.add(k + "=" + URLEncoder.encode(v, StandardCharsets.UTF_8)));
    URI uri = URI.create(base);
    String sep = uri.getRawQuery() == null || uri.getRawQuery().isEmpty() ? "?" : "&";
    String withoutFragment = uri.getRawFragment() == null ? base : base.substring(0, base.indexOf('#'));
    return URI.create(withoutFragment + sep + query);
  }

  /** What a merchant does with the query it received: true only for an untouched, signed result. */
  public static boolean verify(String secret, Map<String, String> query) {
    String signature = query.get("signature");
    if (signature == null) {
      return false;
    }
    try {
      Result r =
          new Result(query.get("sessionId"), query.get("orderId"), query.get("status"), query.get("txnId"),
              Long.parseLong(query.get("amount")), query.get("currency"), query.get("errorCode"),
              Long.parseLong(query.get("ts")));
      return MessageDigest.isEqual(
          sign(secret, r).getBytes(StandardCharsets.US_ASCII), signature.getBytes(StandardCharsets.US_ASCII));
    } catch (RuntimeException e) {
      return false;
    }
  }

  static String canonical(Result r) {
    return String.join(
        "\n",
        PREFIX,
        r.sessionId(),
        r.orderId(),
        r.status(),
        r.txnId() == null ? "" : r.txnId(),
        Long.toString(r.amountMinor()),
        r.currency(),
        r.errorCode() == null ? "" : r.errorCode(),
        Long.toString(r.ts()));
  }

  /** Lower-case hex HMAC-SHA256 of {@code message} (UTF-8) with {@code secret} (UTF-8). */
  public static String hmacHex(String secret, String message) {
    if (secret == null || secret.isEmpty()) {
      throw new IllegalStateException("merchant signing secret is not configured");
    }
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 unavailable", e);
    }
  }
}
