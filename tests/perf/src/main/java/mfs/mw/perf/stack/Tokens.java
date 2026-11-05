package mfs.mw.perf.stack;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Access tokens of Keycloak realm {@code customers} (password grant of the public client {@code
 * mw-app}) through the APISIX route {@code /auth/realms/customers/...}, so their issuer is the one
 * APISIX and mw-app-gateway accept. A background thread renews every token well before it expires
 * (realm lifespan 300 s), so the load's virtual users only ever read a map and never block on
 * Keycloak.
 */
public final class Tokens implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(Tokens.class);
  private static final String PATH = "auth/realms/customers/protocol/openid-connect/token";
  private static final Duration RENEW_BEFORE = Duration.ofSeconds(90);

  private record Token(String value, Instant expires) {}

  private final Map<String, Token> tokens = new ConcurrentHashMap<>();
  private final String password;
  private final ScheduledExecutorService renewer =
      Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("perf-token-renewer").factory());

  public Tokens(String password) {
    this.password = password;
  }

  /** Fetches a token for every user now and keeps renewing them. */
  public void start(Collection<String> usernames) {
    usernames.parallelStream().forEach(this::fetch);
    renewer.scheduleWithFixedDelay(this::renewDue, 15, 15, TimeUnit.SECONDS);
  }

  /** The current token of {@code username}. */
  public String get(String username) {
    Token t = tokens.get(username);
    if (t == null) {
      throw new IllegalStateException("no token for " + username);
    }
    return t.value();
  }

  private void renewDue() {
    Instant due = Instant.now().plus(RENEW_BEFORE);
    tokens.forEach((user, t) -> {
      if (t.expires().isBefore(due)) {
        try {
          fetch(user);
        } catch (RuntimeException e) {
          log.warn("event=perf.token.renew.failed user={} error={}", user, e.getMessage());
        }
      }
    });
  }

  private void fetch(String username) {
    Http.Response r =
        Http.postForm(
            Stack.get().url(PATH),
            Map.of("client_id", "mw-app", "grant_type", "password", "username", username, "password", password));
    if (r.status() != 200 || !r.body().hasNonNull("access_token")) {
      throw new IllegalStateException("no token for " + username + ": HTTP " + r.status());
    }
    long ttl = r.body().path("expires_in").asLong(300);
    tokens.put(username, new Token(r.body().get("access_token").asString(), Instant.now().plusSeconds(ttl)));
  }

  @Override
  public void close() {
    renewer.shutdownNow();
  }
}
