package mfs.mw.e2e.support;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.databind.node.ObjectNode;

/**
 * Partner callbacks (ticket 22) as the utility bill aggregator sends them: {@code POST
 * /partner/billagg/callback/payments} through the APISIX edge, signed hmac-sha256 (Base64 HMAC
 * over method, path, epoch-seconds timestamp and hex SHA-256 of the body; {@code X-Signature},
 * {@code X-Timestamp}) with the dev secret {@code BILLAGG_HMAC_SECRET} from the generated {@code
 * .env}. Also drives the aggregator simulator's callback scenarios and replay.
 */
public final class PartnerCallbacks {

  public static final String PATH = "/partner/billagg/callback/payments";

  /** Where the simulator posts its callbacks: APISIX's internal HTTP listener, as from outside. */
  public static final String SIMULATOR_CALLBACK_URL = "http://apisix:9080" + PATH;

  private PartnerCallbacks() {}

  /** Our partner reference for a transaction ({@code UPAY} + the txnId's hex, upper case). */
  public static String reference(String txnId) {
    return "UPAY" + txnId.replace("-", "").toUpperCase(Locale.ROOT);
  }

  /** The aggregator's callback body for a reference. */
  public static String body(String reference, String status, String code, String paymentRef) {
    ObjectNode n = Http.JSON.createObjectNode();
    n.put("transactionId", reference).put("status", status).put("code", code).put("message", "e2e callback");
    if (paymentRef != null) {
      n.put("paymentRef", paymentRef);
    }
    return Http.JSON.writeValueAsString(n);
  }

  /** Sends {@code body} to the edge, signed with {@code secret}. */
  public static Http.Response send(String body, String secret) {
    String ts = Long.toString(System.currentTimeMillis() / 1000);
    return Http.postRaw(
        Stack.get().url(PATH.substring(1)),
        Map.of("X-Signature", sign(secret, "POST", PATH, ts, body), "X-Timestamp", ts),
        body);
  }

  /** Sends {@code body} signed with the aggregator's real (dev) secret. */
  public static Http.Response sendSigned(String body) {
    return send(body, Stack.get().devCredential("BILLAGG_HMAC_SECRET"));
  }

  /** The aggregator simulator accepts payments with 202 and calls back as {@code mode} says. */
  public static void callbackOnly(String mode, long delayMs) {
    ObjectNode s = Http.JSON.createObjectNode();
    s.put("latencyMs", 0).put("errorRate", 0.0).put("outage", false)
        .put("callbackOnly", true).put("callbackMode", mode)
        .put("callbackUrl", SIMULATOR_CALLBACK_URL).put("callbackDelayMs", delayMs);
    Simulators.setScenario("billagg", s);
  }

  static String sign(String secret, String method, String path, String timestamp, String body) {
    try {
      String bodyHash =
          HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      String toSign = method + "\n" + path + "\n" + timestamp + "\n" + bodyHash;
      return Base64.getEncoder().encodeToString(mac.doFinal(toSign.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }
}
