package mfs.mw.seeder.client;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import mfs.mw.seeder.config.SeederProperties;
import mfs.mw.seeder.util.Totp;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Access tokens of the seed's ops personas: password grant of the dev-only direct-grant client
 * ({@code mw.seeder.operators.client}, {@code mw-e2e-ops}) plus the current TOTP code from the
 * realm's test secret. Keycloak refuses a code a user has already used, so a token is cached per
 * user, a renewal inside the same 30 s step waits for the next one, and a refused grant (the code
 * was used by another login of the user, e.g. a suite or an earlier seed run) is tried once more
 * with the next step's code. The token's {@code iss}
 * carries Keycloak's internal URL; mw-admin-api checks only the realm path of {@code iss}.
 */
@Component
public class OpsTokenClient {

  private record Cached(String token, Instant renewAt, long totpStep) {}

  private final HttpJson http;
  private final URI tokenUri;
  private final SeederProperties.Operators ops;
  private final SeederProperties.Realm realm;
  private final Clock clock;
  private final Map<String, Cached> cache = new HashMap<>();

  public OpsTokenClient(HttpJson http, SeederProperties props, Clock clock) {
    this.http = http;
    this.ops = props.operators();
    this.realm = props.realms().get(ops.realm());
    this.tokenUri =
        props.endpoints().keycloak().resolve("/realms/" + URLEncoder.encode(ops.realm(), StandardCharsets.UTF_8)
            + "/protocol/openid-connect/token");
    this.clock = clock;
  }

  public synchronized String token(String username) {
    Instant now = clock.instant();
    Cached c = cache.get(username);
    if (c != null && now.isBefore(c.renewAt())) {
      return c.token();
    }
    if (realm == null) {
      throw new IllegalStateException("no settings for realm " + ops.realm());
    }
    long step = Totp.step(now.toEpochMilli());
    if (c != null && realm.totp() && step == c.totpStep()) {
      now = nextStep(now);
      step = Totp.step(now.toEpochMilli());
    }
    String call = "POST /realms/{realm}/protocol/openid-connect/token (" + username + ")";
    HttpJson.Response r = http.postForm(call, tokenUri, Map.of(), form(username, step));
    if (realm.totp() && (r.status() == 400 || r.status() == 401)) {
      // the code was used already (another login of this user in the same 30 s step): next step's code
      now = nextStep(now);
      step = Totp.step(now.toEpochMilli());
      r = http.postForm(call, tokenUri, Map.of(), form(username, step));
    }
    JsonNode t = http.tree(call, HttpJson.expect(call, r, 200));
    Cached fresh =
        new Cached(t.path("access_token").asString(), now.plusSeconds(Math.max(5, t.path("expires_in").asLong(60) - 30)), step);
    cache.put(username, fresh);
    return fresh.token();
  }

  private Map<String, String> form(String username, long step) {
    Map<String, String> form = new HashMap<>();
    form.put("grant_type", "password");
    form.put("client_id", ops.client());
    form.put("username", username);
    form.put("password", realm.password());
    if (realm.totp()) {
      form.put("totp", Totp.at(realm.totpSecret(), step));
    }
    return form;
  }

  /** Waits until the TOTP step after the one of {@code now} has begun; returns the time then. */
  private Instant nextStep(Instant now) {
    long step = Totp.step(now.toEpochMilli());
    try {
      Thread.sleep(Math.max(1, (step + 1) * 30_000 - now.toEpochMilli() + 50));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted waiting for the next TOTP step", e);
    }
    return clock.instant();
  }

  /** Forgets a user's token (the API answered 401). */
  public synchronized void invalidate(String username) {
    Cached c = cache.get(username);
    if (c != null) {
      cache.put(username, new Cached(c.token(), Instant.MIN, c.totpStep()));
    }
  }
}
