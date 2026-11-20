package mfs.mw.adapter.interop.client;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import mfs.mw.adapter.interop.config.CryptoClientProperties;
import mfs.mw.adapter.interop.exception.MwException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Client-credentials token for mw-crypto (realm {@code services}, audience {@code mw-crypto}; the
 * mw-data-export copy, ticket 121b). Cached until 30 seconds before it expires. The secret is never
 * logged.
 */
@Component
@ConditionalOnProperty(name = "mw.interop.crypto.enabled", havingValue = "true")
public class ServiceTokenClient {

  static final Duration EARLY = Duration.ofSeconds(30);

  private final CryptoClientProperties auth;
  private final JsonMapper json;
  private final Clock clock;
  private final HttpClient http;
  private String token;
  private Instant renewAt = Instant.MIN;

  public ServiceTokenClient(CryptoClientProperties auth, JsonMapper json, Clock clock) {
    this.auth = auth;
    this.json = json;
    this.clock = clock;
    this.http = HttpClient.newBuilder().connectTimeout(auth.connectTimeout()).build();
  }

  public synchronized String token() {
    Instant now = clock.instant();
    if (token == null || !now.isBefore(renewAt)) {
      fetch(now);
    }
    return token;
  }

  public synchronized void invalidate() {
    token = null;
    renewAt = Instant.MIN;
  }

  private void fetch(Instant now) {
    String secret = auth.clientSecret() == null ? "" : auth.clientSecret();
    String basic = Base64.getEncoder().encodeToString(
        (enc(auth.clientId()) + ":" + enc(secret)).getBytes(StandardCharsets.UTF_8));
    HttpRequest req = HttpRequest.newBuilder(URI.create(auth.tokenUri()))
        .timeout(auth.readTimeout())
        .header("Content-Type", "application/x-www-form-urlencoded")
        .header("Accept", "application/json")
        .header("Authorization", "Basic " + basic)
        .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials"))
        .build();
    HttpResponse<byte[]> res;
    try {
      res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
    } catch (IOException e) {
      throw new MwException(CryptoXmlSigner.CRYPTO_UNAVAILABLE, "token endpoint not reachable", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new MwException(CryptoXmlSigner.CRYPTO_UNAVAILABLE, "token request interrupted", e);
    }
    if (res.statusCode() != 200) {
      throw new MwException(CryptoXmlSigner.CRYPTO_UNAVAILABLE, "token endpoint answered " + res.statusCode());
    }
    JsonNode body = json.readTree(res.body());
    token = body.path("access_token").asString();
    long seconds = body.path("expires_in").asLong(60);
    renewAt = now.plusSeconds(seconds).minus(EARLY);
  }

  private static String enc(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
