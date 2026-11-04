package mfs.mw.e2e.support;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Access tokens of Keycloak realm {@code customers} (password grant of the public client {@code
 * mw-app}), fetched through the APISIX route {@code /auth/realms/customers/...}
 * (deploy/apisix/routes/auth-customers-token.json), so their {@code iss} is the one APISIX and
 * mw-app-gateway accept. Cached per user until shortly before expiry.
 */
public final class Keycloak {

  private static final String TOKEN_PATH = "auth/realms/customers/protocol/openid-connect/token";
  private static final Map<String, Token> CACHE = new ConcurrentHashMap<>();

  private record Token(String value, Instant refreshAt) {}

  private Keycloak() {}

  /** A bearer token for a test user of realm customers (customer01, customer02, ...). */
  public static String customerToken(String username) {
    Token t = CACHE.get(username);
    if (t == null || Instant.now().isAfter(t.refreshAt())) {
      t = fetch(username);
      CACHE.put(username, t);
    }
    return t.value();
  }

  private static Token fetch(String username) {
    Stack stack = Stack.get();
    Http.Response r =
        Http.postForm(
            stack.url(TOKEN_PATH),
            Map.of(
                "client_id", "mw-app",
                "grant_type", "password",
                "username", username,
                "password", stack.devCredential("KC_CUSTOMERS_TEST_PASSWORD")));
    if (r.status() != 200 || !r.body().hasNonNull("access_token")) {
      throw new IllegalStateException("no token for " + username + ": HTTP " + r.status());
    }
    long ttl = r.body().path("expires_in").asLong(60);
    return new Token(
        r.body().get("access_token").asString(),
        Instant.now().plus(Duration.ofSeconds(Math.max(5, ttl - 30))));
  }
}
