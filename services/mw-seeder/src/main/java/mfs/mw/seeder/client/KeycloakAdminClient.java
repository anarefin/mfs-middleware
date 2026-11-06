package mfs.mw.seeder.client;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import mfs.mw.seeder.config.SeederProperties;
import mfs.mw.seeder.dto.NewUser;
import mfs.mw.seeder.exception.RemoteCallException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Keycloak's admin REST API as the bootstrap admin (master realm, password grant of {@code
 * admin-cli}; the token is cached until shortly before it expires and fetched again on a 401).
 * The TOTP credential is created the way the realm imports do (secretData / credentialData), so a
 * persona can log in with a code computed from the realm's test secret.
 */
@Component
public class KeycloakAdminClient implements IdentityAdmin {

  private static final String OTP_DATA =
      "{\"subType\":\"totp\",\"digits\":6,\"counter\":0,\"period\":30,\"algorithm\":\"HmacSHA256\"}";

  private final HttpJson http;
  private final URI base;
  private final SeederProperties.KeycloakAdmin admin;
  private final Clock clock;
  private String token;
  private Instant renewAt = Instant.MIN;

  public KeycloakAdminClient(HttpJson http, SeederProperties props, Clock clock) {
    this.http = http;
    this.base = props.endpoints().keycloak();
    this.admin = props.keycloakAdmin();
    this.clock = clock;
  }

  @Override
  public Optional<String> findUserId(String realm, String username) {
    JsonNode users =
        http.tree("GET users", get("GET /admin/realms/{realm}/users", realm, "/users?exact=true&username=" + enc(username)));
    for (JsonNode u : users) {
      if (username.equalsIgnoreCase(u.path("username").asString(""))) {
        return Optional.of(u.path("id").asString());
      }
    }
    return Optional.empty();
  }

  @Override
  public String createUser(String realm, NewUser user) {
    Map<String, Object> rep = new LinkedHashMap<>();
    rep.put("username", user.username());
    rep.put("enabled", true);
    rep.put("emailVerified", true);
    rep.put("firstName", user.firstName());
    rep.put("lastName", user.lastName());
    rep.put("email", user.email());
    Map<String, List<String>> attributes = new LinkedHashMap<>();
    user.attributes().forEach((k, v) -> attributes.put(k, List.of(v)));
    rep.put("attributes", attributes);
    List<Map<String, Object>> credentials = new ArrayList<>();
    credentials.add(Map.of("type", "password", "value", user.password(), "temporary", false));
    if (user.totpSecret() != null) {
      credentials.add(
          Map.of(
              "type", "otp",
              "userLabel", "seed (dev only)",
              "secretData", http.json().writeValueAsString(Map.of("value", user.totpSecret())),
              "credentialData", OTP_DATA));
    }
    rep.put("credentials", credentials);
    rep.put("requiredActions", List.of());
    String call = "POST /admin/realms/{realm}/users";
    HttpJson.Response r = authed(call, () -> http.sendJson(call, "POST", uri(realm, "/users"), bearer(), rep));
    if (r.status() == 409) {
      // created meanwhile (a retried step whose first answer was lost)
      return findUserId(realm, user.username()).orElseThrow(() -> new RemoteCallException(409, call + " answered 409"));
    }
    HttpJson.expect(call, r, 201);
    String location = r.location().orElseThrow(() -> new RemoteCallException(201, call + " answered no Location"));
    return location.substring(location.lastIndexOf('/') + 1);
  }

  @Override
  public Set<String> realmRoles(String realm, String userId) {
    JsonNode roles =
        http.tree("GET role-mappings", get("GET /admin/realms/{realm}/users/{id}/role-mappings/realm", realm,
            "/users/" + enc(userId) + "/role-mappings/realm"));
    Set<String> names = new HashSet<>();
    roles.forEach(r -> names.add(r.path("name").asString()));
    return names;
  }

  @Override
  public void addRealmRoles(String realm, String userId, Collection<String> roles) {
    List<JsonNode> reps = new ArrayList<>();
    for (String role : roles) {
      reps.add(http.tree("GET role", get("GET /admin/realms/{realm}/roles/{role}", realm, "/roles/" + enc(role))));
    }
    String call = "POST /admin/realms/{realm}/users/{id}/role-mappings/realm";
    URI u = uri(realm, "/users/" + enc(userId) + "/role-mappings/realm");
    HttpJson.expect(call, authed(call, () -> http.sendJson(call, "POST", u, bearer(), reps)), 204);
  }

  @Override
  public boolean clientExists(String realm, String clientId) {
    return !http.tree("GET clients", get("GET /admin/realms/{realm}/clients", realm, "/clients?clientId=" + enc(clientId)))
        .isEmpty();
  }

  private HttpJson.Response get(String call, String realm, String path) {
    URI u = uri(realm, path);
    return HttpJson.expect(call, authed(call, () -> http.get(call, u, bearer())), 200);
  }

  /** Runs the call; on a 401 (token revoked or expired early) fetches a new token and runs it once more. */
  private HttpJson.Response authed(String call, java.util.function.Supplier<HttpJson.Response> send) {
    HttpJson.Response r = send.get();
    if (r.status() == 401) {
      synchronized (this) {
        token = null;
      }
      r = send.get();
    }
    return r;
  }

  private Map<String, String> bearer() {
    return Map.of("Authorization", "Bearer " + token());
  }

  private synchronized String token() {
    Instant now = clock.instant();
    if (token == null || !now.isBefore(renewAt)) {
      if (admin.password() == null || admin.password().isBlank()) {
        throw new IllegalStateException("no Keycloak admin password (KC_BOOTSTRAP_ADMIN_PASSWORD)");
      }
      String call = "POST /realms/{realm}/protocol/openid-connect/token (admin)";
      HttpJson.Response r =
          HttpJson.expect(
              call,
              http.postForm(
                  call,
                  base.resolve("/realms/" + enc(admin.realm()) + "/protocol/openid-connect/token"),
                  Map.of(),
                  Map.of(
                      "grant_type", "password",
                      "client_id", admin.clientId(),
                      "username", admin.username(),
                      "password", admin.password())),
              200);
      JsonNode t = http.tree(call, r);
      token = t.path("access_token").asString();
      renewAt = now.plusSeconds(Math.max(5, t.path("expires_in").asLong(60) - 15));
    }
    return token;
  }

  private URI uri(String realm, String path) {
    return base.resolve("/admin/realms/" + enc(realm) + path);
  }

  private static String enc(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }
}
