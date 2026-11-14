package mfs.mw.audit.dw.client;

import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import mfs.mw.audit.dw.config.AnchorProperties;
import mfs.mw.audit.dw.exception.CryptoUnavailableException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * This service's access token for mw-crypto (spec 18.2; copied from mw-admin-api, D14): Keycloak
 * realm {@code services}, client credentials, client authenticated with HTTP Basic, audience
 * {@code mw-crypto}. Cached until {@link #EARLY} before it expires; {@link #invalidate()} drops it
 * (mw-crypto answered 401). Keycloak is plain HTTP in the dev stack, so this call carries no client
 * certificate; only the mw-crypto call does. Failures are {@link CryptoUnavailableException}; the
 * secret never appears in a message or log.
 */
@Component
@ConditionalOnProperty(name = "mw.audit-dw.anchor.enabled", havingValue = "true", matchIfMissing = true)
public class ServiceTokenClient {

  /** A token is renewed this long before its {@code expires_in} runs out. */
  static final Duration EARLY = Duration.ofSeconds(30);

  private final AnchorProperties.Crypto auth;
  private final JsonMapper json;
  private final Clock clock;
  private final HttpClient http;

  private String token;
  private Instant renewAt = Instant.MIN;

  public ServiceTokenClient(AnchorProperties props, JsonMapper json, Clock clock) {
    this.auth = props.crypto();
    this.json = json;
    this.clock = clock;
    this.http =
        HttpClient.newBuilder().connectTimeout(auth.connectTimeout()).followRedirects(HttpClient.Redirect.NEVER).build();
  }

  /** A valid access token, fetched when none is cached or the cached one is about to expire. */
  public synchronized String token() {
    Instant now = clock.instant();
    if (token == null || !now.isBefore(renewAt)) {
      fetch(now);
    }
    return token;
  }

  /** Forgets the cached token; the next call fetches a new one. */
  public synchronized void invalidate() {
    token = null;
    renewAt = Instant.MIN;
  }

  private void fetch(Instant now) {
    String secret = auth.clientSecret() == null ? "" : auth.clientSecret();
    String basic =
        Base64.getEncoder()
            .encodeToString((enc(auth.clientId()) + ":" + enc(secret)).getBytes(StandardCharsets.UTF_8));
    HttpRequest req =
        HttpRequest.newBuilder(auth.tokenUri())
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
      throw new CryptoUnavailableException("token endpoint not reachable", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new CryptoUnavailableException("interrupted", e);
    }
    if (res.statusCode() != 200) {
      throw new CryptoUnavailableException("token endpoint answered " + res.statusCode(), null);
    }
    try {
      JsonNode body = json.readTree(res.body());
      String access = body.path("access_token").asString("");
      long expiresIn = body.path("expires_in").asLong(60);
      if (access.isBlank()) {
        throw new CryptoUnavailableException("token endpoint gave no token", null);
      }
      token = access;
      renewAt = now.plusSeconds(Math.max(0, expiresIn - EARLY.toSeconds()));
    } catch (JacksonException e) {
      throw new CryptoUnavailableException("token answer unreadable", e);
    }
  }

  /** RFC 6749 2.3.1: client id and secret are form-encoded before Basic. */
  private static String enc(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }
}
