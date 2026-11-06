package mfs.mw.e2e.support;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.databind.JsonNode;

/**
 * The merchant IPN receiver in mw-simulators (ticket 32) through the dev-only route {@code
 * /test/sim/*}: checkout sessions of the suite point their {@code ipnUrl} at {@link #URL};
 * {@code POST /sim/merchant/ipn/scenario {failNext, failStatus, ipnId}} makes it fail the next N
 * IPNs of one session, {@code GET /sim/merchant/ipn/received?ipnId=} lists what it received.
 * {@link #verify} is the merchant's signature check, written from the contract.
 */
public final class MerchantIpn {

  /** Where the suite's sessions send their IPN (plain http: the dev stack allows this host). */
  public static final String URL = "http://mw-simulators:8080/merchant/ipn/e2e";

  /** One IPN as the merchant received it, with the status it answered. */
  public record Received(int attempt, String timestamp, String signature, String body, int answeredStatus) {}

  private static final String BASE = "test/sim/merchant/ipn/";

  private MerchantIpn() {}

  public static void failNext(String ipnId, int count, int status) {
    var b = Http.JSON.createObjectNode().put("failNext", count).put("failStatus", status).put("ipnId", ipnId);
    Http.Response r = Http.postJson(Stack.get().url(BASE + "scenario"), auth(), b);
    if (r.status() != 200) {
      throw new IllegalStateException("merchant IPN scenario not accepted: " + r);
    }
  }

  /** Back to accepting every IPN. */
  public static void acceptAll() {
    var b = Http.JSON.createObjectNode().put("failNext", 0);
    Http.postJson(Stack.get().url(BASE + "scenario"), auth(), b);
  }

  public static List<Received> received(String ipnId) {
    Http.Response r = Http.get(Stack.get().url(BASE + "received?ipnId=" + ipnId), auth());
    if (r.status() != 200) {
      throw new IllegalStateException("merchant IPN query failed: " + r);
    }
    List<Received> out = new ArrayList<>();
    for (JsonNode n : r.body()) {
      JsonNode h = n.path("headers");
      out.add(new Received(h.path("x-bits-attempt").asInt(-1), h.path("x-bits-timestamp").asString(null),
          h.path("x-bits-signature").asString(null), n.path("body").asString(), n.path("answeredStatus").asInt()));
    }
    return out;
  }

  /**
   * The merchant's check: hex HMAC-SHA256 with its secret over {@code
   * bits-checkout-ipn.v1\n<X-Bits-Timestamp>\n<body>}, compared in constant time.
   */
  public static boolean verify(Received ipn, String secret) {
    if (ipn.signature() == null || ipn.timestamp() == null) {
      return false;
    }
    String expected = HexFormat.of().formatHex(hmac(secret, "bits-checkout-ipn.v1\n" + ipn.timestamp() + "\n" + ipn.body()));
    return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), ipn.signature().getBytes(StandardCharsets.US_ASCII));
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

  private static Map<String, String> auth() {
    return Map.of("apikey", Stack.get().devCredential("MW_E2E_API_KEY"));
  }
}
