package mfs.mw.perf.stack;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import mfs.mw.perf.app.Device;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * mw-auth access tokens of the perf app users (ticket 73: the Keycloak customers realm is gone; the
 * apps log in at mw-auth with MSISDN + PIN on a bound device, spec 18.7). {@code Provisioning} logs
 * each user in and hands the token response over ({@link #put}); a background thread renews every
 * token well before it expires (5 min) with its rotating refresh token, signed by the user's device
 * ({@code POST /auth/v1/tokens/refresh}), so the load's virtual users only ever read a map and never
 * block on mw-auth.
 */
public final class Tokens implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(Tokens.class);
  private static final String REFRESH = "/auth/v1/tokens/refresh";
  private static final Duration RENEW_BEFORE = Duration.ofSeconds(90);

  private record Token(Device device, String access, String refresh, Instant expires) {}

  private final Map<String, Token> tokens = new ConcurrentHashMap<>();
  private final ScheduledExecutorService renewer =
      Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("perf-token-renewer").factory());

  /** Keeps the tokens of a fresh login (an mw-auth {@code TokenResponse}) of {@code username}. */
  public void put(String username, Device device, JsonNode tokenResponse) {
    tokens.put(username, token(username, device, tokenResponse));
  }

  /** Starts renewing the tokens put so far. */
  public void start() {
    renewer.scheduleWithFixedDelay(this::renewDue, 15, 15, TimeUnit.SECONDS);
  }

  /** The current access token of {@code username}. */
  public String get(String username) {
    Token t = tokens.get(username);
    if (t == null) {
      throw new IllegalStateException("no token for " + username);
    }
    return t.access();
  }

  private void renewDue() {
    Instant due = Instant.now().plus(RENEW_BEFORE);
    tokens.forEach((user, t) -> {
      if (t.expires().isBefore(due)) {
        try {
          refresh(user, t);
        } catch (RuntimeException e) {
          log.warn("event=perf.token.renew.failed user={} error={}", user, e.getMessage());
        }
      }
    });
  }

  private void refresh(String username, Token t) {
    String body = Http.JSON.createObjectNode().put("refreshToken", t.refresh()).toString();
    Http.Response r =
        Http.postRaw(Stack.get().url(REFRESH.substring(1)), t.device().signedHeaders("POST", REFRESH, body), body);
    if (r.status() != 200) {
      throw new IllegalStateException("no refreshed token for " + username + ": HTTP " + r.status());
    }
    tokens.put(username, token(username, t.device(), r.body()));
  }

  private static Token token(String username, Device device, JsonNode b) {
    if (!b.hasNonNull("accessToken") || !b.hasNonNull("refreshToken")) {
      throw new IllegalStateException("no mw-auth tokens for " + username);
    }
    long ttl = b.path("expiresIn").asLong(300);
    return new Token(device, b.get("accessToken").asString(), b.get("refreshToken").asString(), Instant.now().plusSeconds(ttl));
  }

  @Override
  public void close() {
    renewer.shutdownNow();
  }
}
