package mfs.mw.e2e.support;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.databind.node.ObjectNode;

/**
 * Web checkout (ticket 31) as a merchant server and the hosted page use it, through the edge:
 *
 * <ul>
 *   <li>merchant API {@code /checkout/v1/*}, signed for APISIX {@code hmac-auth} as the dev merchant
 *       consumer {@code merchant_demo} (key id {@code merchant-demo}, secret {@code
 *       CHECKOUT_DEMO_MERCHANT_SECRET} from the generated {@code .env}; the consumer exists after
 *       {@code make e2e} re-ran apisix-init with {@code MW_E2E_ROUTES=1});
 *   <li>hosted page API {@code /checkout/pay/v1/{token}/*}, no login;
 *   <li>{@link #verifyResult}: what the merchant does with the signed redirect (contract {@code
 *       contracts/api/mw-checkout/checkout.v1.openapi.yaml}), written here from the contract, not
 *       copied from the service.
 * </ul>
 */
public final class CheckoutApi {

  public static final String KEY_ID = "merchant-demo";
  public static final String SESSIONS = "/checkout/v1/sessions";

  private static final DateTimeFormatter HTTP_DATE =
      DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);

  private CheckoutApi() {}

  public static String secret() {
    return Stack.get().devCredential("CHECKOUT_DEMO_MERCHANT_SECRET");
  }

  /** A session request for {@code orderId} (English, so the OTP SMS is GSM-7). */
  public static String sessionBody(String orderId, long amountMinor) {
    ObjectNode b = Http.JSON.createObjectNode();
    b.put("orderId", orderId);
    b.putObject("amount").put("valueMinor", amountMinor).put("currency", "BDT");
    b.put("successUrl", "https://shop.example.test/checkout/ok?cart=7");
    b.put("failUrl", "https://shop.example.test/checkout/fail");
    b.put("cancelUrl", "https://shop.example.test/checkout/cancel");
    b.put("ipnUrl", "https://shop.example.test/checkout/ipn");
    b.put("description", "e2e order " + orderId);
    b.put("locale", "en");
    return Http.JSON.writeValueAsString(b);
  }

  /** POST /checkout/v1/sessions signed with {@code secret} (a wrong one for the bad-HMAC case). */
  public static Http.Response createSession(String body, String secret) {
    return Http.postRaw(Stack.get().url(SESSIONS.substring(1)), signedHeaders("POST", SESSIONS, body, secret), body);
  }

  /** POST /checkout/v1/sessions without any signature. */
  public static Http.Response createSessionUnsigned(String body) {
    return Http.postRaw(Stack.get().url(SESSIONS.substring(1)), Map.of(), body);
  }

  public static Http.Response getSession(String sessionId) {
    String path = SESSIONS + "/" + sessionId;
    return Http.get(Stack.get().url(path.substring(1)), signedHeaders("GET", path, null, secret()));
  }

  public static Http.Response page(String token) {
    return Http.get(Stack.get().url("checkout/pay/v1/" + token), Map.of());
  }

  public static Http.Response requestOtp(String token, String wallet) {
    ObjectNode b = Http.JSON.createObjectNode().put("wallet", wallet);
    return Http.postJson(Stack.get().url("checkout/pay/v1/" + token + "/otp"), Map.of(), b);
  }

  public static Http.Response confirm(String token, String otp, String pin) {
    ObjectNode b = Http.JSON.createObjectNode().put("otp", otp);
    ObjectNode p = b.putObject("encryptedPin");
    CoreMock.encryptPin(pin).forEach(p::put);
    return Http.postJson(Stack.get().url("checkout/pay/v1/" + token + "/confirm"), Map.of(), b);
  }

  /** GET .../return: the browser's redirect (the client does not follow it). */
  public static Http.Response back(String token) {
    return Http.get(Stack.get().url("checkout/pay/v1/" + token + "/return"), Map.of());
  }

  /** The pay token is the last path segment of checkoutUrl. */
  public static String token(String checkoutUrl) {
    return checkoutUrl.substring(checkoutUrl.lastIndexOf('/') + 1);
  }

  /**
   * APISIX hmac-auth headers: {@code Date}, {@code Digest} (POST) and {@code Authorization:
   * Signature ...} over {@code keyId\n<METHOD> <path>\ndate: <Date>\n[digest: <Digest>\n]}.
   */
  static Map<String, String> signedHeaders(String method, String path, String body, String secret) {
    String date = HTTP_DATE.format(ZonedDateTime.now(ZoneOffset.UTC));
    Map<String, String> h = new LinkedHashMap<>();
    h.put("Date", date);
    StringBuilder signing = new StringBuilder(KEY_ID).append('\n').append(method).append(' ').append(path).append('\n');
    signing.append("date: ").append(date).append('\n');
    String headers = "@request-target date";
    if (body != null) {
      String digest = "SHA-256=" + Base64.getEncoder().encodeToString(sha256(body.getBytes(StandardCharsets.UTF_8)));
      h.put("Digest", digest);
      signing.append("digest: ").append(digest).append('\n');
      headers += " digest";
    }
    String signature = Base64.getEncoder().encodeToString(hmac(secret, signing.toString()));
    h.put("Authorization",
        "Signature keyId=\"" + KEY_ID + "\",algorithm=\"hmac-sha256\",headers=\"" + headers + "\",signature=\"" + signature + "\"");
    return h;
  }

  /** The query of a redirect URL, decoded. */
  public static Map<String, String> query(String url) {
    Map<String, String> out = new LinkedHashMap<>();
    String raw = URI.create(url).getRawQuery();
    for (String pair : raw == null ? new String[0] : raw.split("&")) {
      int eq = pair.indexOf('=');
      out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
          URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
    }
    return out;
  }

  /**
   * The merchant's check of the signed result: hex HMAC-SHA256 with its secret over {@code
   * upay-checkout-result.v1\nsessionId\norderId\nstatus\ntxnId\namount\ncurrency\nerrorCode\nts}.
   */
  public static boolean verifyResult(Map<String, String> q, String secret) {
    String canonical = String.join("\n", List.of(
        "upay-checkout-result.v1",
        q.getOrDefault("sessionId", ""), q.getOrDefault("orderId", ""), q.getOrDefault("status", ""),
        q.getOrDefault("txnId", ""), q.getOrDefault("amount", ""), q.getOrDefault("currency", ""),
        q.getOrDefault("errorCode", ""), q.getOrDefault("ts", "")));
    String expected = HexFormat.of().formatHex(hmac(secret, canonical));
    String got = q.get("signature");
    return got != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), got.getBytes(StandardCharsets.US_ASCII));
  }

  private static byte[] hmac(String secret, String text) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return mac.doFinal(text.getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  private static byte[] sha256(byte[] b) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(b);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }
}
