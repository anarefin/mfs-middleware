package mfs.mw.adapter.remit.client;

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
import mfs.mw.adapter.remit.config.CoreClientProperties;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * This service's access token for the core wallet API (ticket 119c; mw-app-gateway's copy, D14):
 * Keycloak realm {@code services}, client credentials with HTTP Basic, audience {@code mw-core-mock}
 * (the mapper on the client). Cached until {@link #EARLY} before it expires; {@link #invalidate()}
 * drops it (the core answered 401). A failure is {@link CoreUnavailableException}; the secret never
 * appears in a message or log.
 */
@Component
public class ServiceTokenClient {

  /** A token is renewed this long before its {@code expires_in} runs out. */
  static final Duration EARLY = Duration.ofSeconds(30);

  private final CoreClientProperties props;
  private final JsonMapper json;
  private final Clock clock;
  private final HttpClient http;

  private String token;
  private Instant renewAt = Instant.MIN;

  public ServiceTokenClient(CoreClientProperties props, JsonMapper json, Clock clock) {
    this.props = props;
    this.json = json;
    this.clock = clock;
    this.http =
        HttpClient.newBuilder().connectTimeout(props.connectTimeout()).followRedirects(HttpClient.Redirect.NEVER).build();
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
    String basic =
        Base64.getEncoder()
            .encodeToString((enc(props.clientId()) + ":" + enc(props.clientSecret())).getBytes(StandardCharsets.UTF_8));
    HttpRequest req =
        HttpRequest.newBuilder(props.tokenUri())
            .timeout(props.readTimeout())
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .header("Authorization", "Basic " + basic)
            .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials"))
            .build();
    HttpResponse<byte[]> res;
    try {
      res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
    } catch (IOException e) {
      throw new CoreUnavailableException("token endpoint not reachable", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new CoreUnavailableException("interrupted", e);
    }
    if (res.statusCode() != 200) {
      throw new CoreUnavailableException("token endpoint answered " + res.statusCode(), null);
    }
    try {
      JsonNode body = json.readTree(res.body());
      String access = body.path("access_token").asString("");
      long expiresIn = body.path("expires_in").asLong(60);
      if (access.isBlank()) {
        throw new CoreUnavailableException("token endpoint gave no token", null);
      }
      token = access;
      renewAt = now.plusSeconds(Math.max(0, expiresIn - EARLY.toSeconds()));
    } catch (JacksonException e) {
      throw new CoreUnavailableException("token answer unreadable", e);
    }
  }

  /** RFC 6749 2.3.1: client id and secret are form-encoded before Basic. */
  private static String enc(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }
}
