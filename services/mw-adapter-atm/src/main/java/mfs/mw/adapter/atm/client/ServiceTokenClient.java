package mfs.mw.adapter.atm.client;

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
import mfs.mw.adapter.atm.config.AuthClientProperties;
import mfs.mw.adapter.atm.exception.MwException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Client-credentials token for mw-auth (realm {@code services}; the mw-adapter-interop copy, ticket
 * 120b2). Cached until 30 seconds before it expires. The secret is never logged.
 */
@Component
public class ServiceTokenClient {

  static final Duration EARLY = Duration.ofSeconds(30);

  private final AuthClientProperties auth;
  private final JsonMapper json;
  private final Clock clock;
  private final HttpClient http;
  private String token;
  private Instant renewAt = Instant.MIN;

  public ServiceTokenClient(AuthClientProperties auth, JsonMapper json, Clock clock) {
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
      throw unavailable("token endpoint not reachable", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw unavailable("token request interrupted", e);
    }
    if (res.statusCode() != 200) {
      throw unavailable("token endpoint answered " + res.statusCode(), null);
    }
    JsonNode body = json.readTree(res.body());
    String value = body.path("access_token").asString("");
    if (value.isEmpty()) {
      throw unavailable("token endpoint answered no token", null);
    }
    token = value;
    renewAt = now.plusSeconds(body.path("expires_in").asLong(60)).minus(EARLY);
  }

  private static MwException unavailable(String message, Throwable cause) {
    return new MwException(AtmTokenClient.UNAVAILABLE, message, cause).withStatus(HttpStatus.SERVICE_UNAVAILABLE);
  }

  private static String enc(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
