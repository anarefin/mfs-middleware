package mfs.mw.admin.api.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import mfs.mw.admin.api.config.CredentialProperties;
import mfs.mw.admin.api.exception.CredentialVaultUnavailableException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.vault.authentication.SessionManager;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link CredentialVault} over Vault's KV version 2 HTTP API (ticket 131), with the JDK client
 * (PATCH is needed and the write must never read). Token: {@code mw.credentials.vault.token} when
 * set (tests), else the Spring Cloud Vault session (AppRole, the service's own policy).
 *
 * <ul>
 *   <li>{@link #writeFields}: {@code PATCH <mount>/data/<path>} (JSON merge patch: other fields of
 *       the secret stay; needs only the {@code patch} capability); a 404 (no such secret yet) is
 *       followed by a create with {@code cas=0} (only if still absent; a lost race patches again).
 *   <li>staging: {@code POST} / {@code GET <mount>/data/<staging>/<ref>} and {@code DELETE
 *       <mount>/metadata/<staging>/<ref>} (every version).
 * </ul>
 */
@Component
public class VaultCredentialClient implements CredentialVault {

  private static final TypeReference<Map<String, String>> FIELDS = new TypeReference<>() {};
  private static final Pattern SEGMENTS = Pattern.compile("^[a-z0-9][a-z0-9-]{0,63}(/[a-z0-9][a-z0-9-]{0,63})*$");

  private final CredentialProperties.Vault props;
  private final ObjectProvider<SessionManager> sessions;
  private final JsonMapper json;
  private final HttpClient http;

  public VaultCredentialClient(CredentialProperties props, ObjectProvider<SessionManager> sessions, JsonMapper json) {
    this.props = props.vault();
    this.sessions = sessions;
    this.json = json;
    this.http = HttpClient.newBuilder().connectTimeout(this.props.connectTimeout())
        .followRedirects(HttpClient.Redirect.NEVER).build();
  }

  @Override
  public void stage(String ref, Map<String, String> fields) {
    HttpResponse<String> r = send("POST", "data/" + staging(ref), "application/json", body(fields, null));
    expect2xx(r, "stage");
  }

  @Override
  public Optional<Map<String, String>> staged(String ref) {
    HttpResponse<String> r = send("GET", "data/" + staging(ref), null, null);
    if (r.statusCode() == 404) {
      return Optional.empty();
    }
    expect2xx(r, "read staged");
    JsonNode data = json.readTree(r.body()).path("data").path("data");
    return data.isObject() && !data.isEmpty() ? Optional.of(json.convertValue(data, FIELDS)) : Optional.empty();
  }

  @Override
  public void dropStaged(String ref) {
    HttpResponse<String> r = send("DELETE", "metadata/" + staging(ref), null, null);
    if (r.statusCode() != 404) {
      expect2xx(r, "drop staged");
    }
  }

  @Override
  public void writeFields(String path, Map<String, String> fields) {
    String data = "data/" + checked(path);
    for (int attempt = 0; attempt < 2; attempt++) {
      HttpResponse<String> patched = send("PATCH", data, "application/merge-patch+json", body(fields, null));
      if (patched.statusCode() != 404) {
        expect2xx(patched, "patch");
        return;
      }
      HttpResponse<String> created = send("POST", data, "application/json", body(fields, 0));
      if (created.statusCode() / 100 == 2) {
        return;
      }
      if (created.statusCode() != 400) {
        expect2xx(created, "create");
      }
      // 400: check-and-set lost (created meanwhile): patch again
    }
    throw new CredentialVaultUnavailableException("Vault write did not settle");
  }

  private String body(Map<String, String> fields, Integer cas) {
    Map<String, Object> b = new LinkedHashMap<>();
    if (cas != null) {
      b.put("options", Map.of("cas", cas));
    }
    b.put("data", fields);
    return json.writeValueAsString(b);
  }

  private String staging(String ref) {
    return checked(props.stagingPath() + "/" + ref);
  }

  /** Only plain segments reach the URL: no traversal, encoding or query. */
  private static String checked(String path) {
    if (path == null || !SEGMENTS.matcher(path).matches()) {
      throw new IllegalArgumentException("not a plain Vault path");
    }
    return path;
  }

  private HttpResponse<String> send(String method, String path, String contentType, String body) {
    if (!props.enabled()) {
      throw new CredentialVaultUnavailableException("the credential store is not enabled");
    }
    URI uri = props.uri().resolve("/v1/" + props.kvMount() + "/" + path);
    HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(props.readTimeout()).header("X-Vault-Token", token());
    if (contentType != null) {
      b.header("Content-Type", contentType);
    }
    b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
    try {
      return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new CredentialVaultUnavailableException("Vault " + method + " failed: " + e.getClass().getSimpleName(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new CredentialVaultUnavailableException("Vault " + method + " interrupted", e);
    }
  }

  private String token() {
    if (!props.token().isBlank()) {
      return props.token();
    }
    SessionManager session = sessions.getIfAvailable();
    if (session == null) {
      throw new CredentialVaultUnavailableException("no Vault session (Spring Cloud Vault is off)");
    }
    return session.getSessionToken().getToken();
  }

  private static void expect2xx(HttpResponse<String> r, String what) {
    if (r.statusCode() / 100 != 2) {
      throw new CredentialVaultUnavailableException("Vault " + what + " answered " + r.statusCode());
    }
  }
}
