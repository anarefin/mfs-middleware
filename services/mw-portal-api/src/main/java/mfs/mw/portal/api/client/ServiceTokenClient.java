package mfs.mw.portal.api.client;

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
import mfs.mw.portal.api.config.OrchestratorClientProperties;
import mfs.mw.portal.api.exception.OrchestratorUnavailableException;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * This service's access token for mw-orchestrator's internal API (spec 18.2, M3 gate decision):
 * Keycloak realm {@code services}, client credentials, client authenticated with HTTP Basic. The
 * token is cached and reused until {@link #EARLY} before it expires; {@link #invalidate()} drops
 * it (the orchestrator answered 401). Failures are {@link OrchestratorUnavailableException} (the
 * query answers 503, retryable); the secret never appears in a message or log.
 */
@Component
public class ServiceTokenClient {

  /** A token is renewed this long before its {@code expires_in} runs out. */
  static final Duration EARLY = Duration.ofSeconds(30);

  private final OrchestratorClientProperties.Auth auth;
  private final JsonMapper json;
  private final Clock clock;
  private final HttpClient http;
  private final Duration timeout;

  private String token;
  private Instant renewAt = Instant.MIN;

  public ServiceTokenClient(OrchestratorClientProperties props, JsonMapper json, Clock clock) {
    this.auth = props.auth();
    this.json = json;
    this.clock = clock;
    this.timeout = props.readTimeout();
    this.http =
        HttpClient.newBuilder()
            .connectTimeout(props.connectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
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
            .encodeToString(
                (enc(auth.clientId()) + ":" + enc(auth.clientSecret())).getBytes(StandardCharsets.UTF_8));
    HttpRequest req =
        HttpRequest.newBuilder(auth.tokenUri())
            .timeout(timeout)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .header("Authorization", "Basic " + basic)
            .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials"))
            .build();
    HttpResponse<byte[]> res;
    try {
      res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
    } catch (IOException e) {
      throw new OrchestratorUnavailableException("token endpoint not reachable", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new OrchestratorUnavailableException("interrupted", e);
    }
    if (res.statusCode() != 200) {
      throw new OrchestratorUnavailableException("token endpoint answered " + res.statusCode(), null);
    }
    try {
      JsonNode body = json.readTree(res.body());
      String access = body.path("access_token").asString("");
      long expiresIn = body.path("expires_in").asLong(60);
      if (access.isBlank()) {
        throw new OrchestratorUnavailableException("token endpoint gave no token", null);
      }
      token = access;
      renewAt = now.plusSeconds(Math.max(0, expiresIn - EARLY.toSeconds()));
    } catch (JacksonException e) {
      throw new OrchestratorUnavailableException("token answer unreadable", e);
    }
  }

  /** RFC 6749 2.3.1: client id and secret are form-encoded before Basic. */
  private static String enc(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }
}
