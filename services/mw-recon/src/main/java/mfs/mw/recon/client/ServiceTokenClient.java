package mfs.mw.recon.client;

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
import mfs.mw.recon.config.CoreClientProperties;
import mfs.mw.recon.exception.ErrorCodes;
import mfs.mw.recon.exception.MwException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * This service's access token for the core wallet API (spec 18.2; mw-auth's pattern, copied, D14): Keycloak realm
 * {@code services}, client credentials with HTTP Basic, audience {@code mw-core-mock} (mapper {@code
 * aud-mw-core-mock} on client mw-recon). Cached until {@link #EARLY} before it expires; {@link #invalidate()} drops
 * it. Failures are {@code MW-SYS-002} (503); the secret never appears in a message or log.
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
    this.http = HttpClient.newBuilder().connectTimeout(props.connectTimeout())
        .followRedirects(HttpClient.Redirect.NEVER).build();
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
    String basic = Base64.getEncoder().encodeToString(
        (enc(props.clientId()) + ":" + enc(props.clientSecret())).getBytes(StandardCharsets.UTF_8));
    HttpRequest req = HttpRequest.newBuilder(props.tokenUri())
        .timeout(props.timeout())
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
      throw unavailable("interrupted", e);
    }
    if (res.statusCode() != 200) {
      throw unavailable("token endpoint answered " + res.statusCode(), null);
    }
    try {
      JsonNode body = json.readTree(res.body());
      String access = body.path("access_token").asString("");
      long expiresIn = body.path("expires_in").asLong(60);
      if (access.isBlank()) {
        throw unavailable("token endpoint gave no token", null);
      }
      token = access;
      renewAt = now.plusSeconds(Math.max(0, expiresIn - EARLY.toSeconds()));
    } catch (JacksonException e) {
      throw unavailable("token answer unreadable", e);
    }
  }

  private static MwException unavailable(String message, Throwable cause) {
    return new MwException(ErrorCodes.SYS_UPSTREAM, "Service token: " + message, cause)
        .withStatus(HttpStatus.SERVICE_UNAVAILABLE);
  }

  /** RFC 6749 2.3.1: client id and secret are form-encoded before Basic. */
  private static String enc(String s) {
    return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
  }
}
