package mfs.mw.simulators.client;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Signs a simulator callback the way the middleware's {@code hmac-sha256} auth scheme verifies it
 * (ticket 22): string to sign = method, path and query of the callback URL, epoch-seconds
 * timestamp, the nonce, lower-case hex SHA-256 of the body, one per line; Base64 HMAC-SHA256 in
 * {@code X-Signature}, the timestamp in {@code X-Timestamp}, the nonce in {@code X-Nonce} (ticket
 * 99, FRS PLT-008: a random UUID per send, so a DUPLICATE or replayed callback is a partner's
 * resend the adapter dedups by reference, never a replayed request it refuses). The secret is the property {@code
 * sim.callback-secrets.<partner>} (Vault {@code secret/mw-simulators} in the stack); a partner
 * without one sends unsigned callbacks (the OCS). Values are never logged.
 */
@Component
public class CallbackSigner {

  public static final String PREFIX = "sim.callback-secrets.";

  private final Environment environment;
  private final Clock clock;

  public CallbackSigner(Environment environment, Clock clock) {
    this.environment = environment;
    this.clock = clock;
  }

  /** The signature headers for posting {@code body} to {@code url}; empty when no secret is set. */
  public Map<String, String> headers(String partner, String url, String body) {
    String secret = environment.getProperty(PREFIX + partner);
    if (secret == null || secret.isEmpty()) {
      return Map.of();
    }
    URI uri = URI.create(url);
    String path = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
    String ts = Long.toString(clock.instant().getEpochSecond());
    String nonce = UUID.randomUUID().toString();
    return Map.of("X-Signature", sign(secret, "POST", path, ts, nonce, body), "X-Timestamp", ts, "X-Nonce", nonce);
  }

  static String sign(String secret, String method, String path, String timestamp, String nonce, String body) {
    try {
      String bodyHash =
          HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      String toSign = method + "\n" + path + "\n" + timestamp + "\n" + nonce + "\n" + bodyHash;
      return Base64.getEncoder().encodeToString(mac.doFinal(toSign.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HMAC-SHA256 unavailable", e);
    }
  }
}
