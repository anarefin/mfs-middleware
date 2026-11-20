package mfs.mw.admin.api.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import mfs.mw.admin.api.config.ApisixProperties;
import mfs.mw.admin.api.exception.ApisixUnavailableException;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The APISIX Admin API over HTTP (ticket 100a): {@code /apisix/admin/<kind>/<id>} with the admin
 * key in {@code X-API-KEY} (never logged; the Admin API only accepts callers from the {@code ops}
 * subnet). Answers are {@code {"key", "value"}} for one object and {@code {"total", "list":
 * [{"key", "value"}]}} for a list. A 404 on read or delete is "not there"; anything else that is not
 * 2xx, or no answer, is an {@link ApisixUnavailableException} carrying the Admin API's {@code
 * error_msg} only.
 */
@Component
public class ApisixAdminClient implements ApisixAdmin {

  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

  private final ApisixProperties props;
  private final JsonMapper json;
  private final HttpClient http;

  public ApisixAdminClient(ApisixProperties props, JsonMapper json) {
    this.props = props;
    this.json = json;
    this.http =
        HttpClient.newBuilder().connectTimeout(props.connectTimeout()).followRedirects(HttpClient.Redirect.NEVER).build();
  }

  @Override
  public Optional<Map<String, Object>> get(Kind kind, String id) {
    HttpResponse<String> r = send("GET", kind, id, null);
    if (r.statusCode() == 404) {
      return Optional.empty();
    }
    JsonNode value = read(r).path("value");
    return value.isObject() ? Optional.of(json.convertValue(value, MAP)) : Optional.empty();
  }

  @Override
  public void put(Kind kind, String id, Map<String, Object> body) {
    send("PUT", kind, id, json.writeValueAsString(body));
  }

  @Override
  public void delete(Kind kind, String id) {
    send("DELETE", kind, id, null);
  }

  @Override
  public List<Map<String, Object>> list(Kind kind) {
    JsonNode list = read(send("GET", kind, null, null)).path("list");
    List<Map<String, Object>> out = new ArrayList<>();
    if (list.isArray()) {
      for (JsonNode item : list) {
        if (item.path("value").isObject()) {
          out.add(json.convertValue(item.path("value"), MAP));
        }
      }
    }
    return out;
  }

  private HttpResponse<String> send(String method, Kind kind, String id, String body) {
    URI uri = props.adminUrl().resolve("/apisix/admin/" + kind.path() + (id == null ? "" : "/" + id));
    HttpRequest.Builder b =
        HttpRequest.newBuilder(uri)
            .timeout(props.readTimeout())
            .header("X-API-KEY", props.adminKey())
            .header("Accept", "application/json");
    if (body != null) {
      b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
    } else {
      b.method(method, HttpRequest.BodyPublishers.noBody());
    }
    HttpResponse<String> r;
    try {
      r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new ApisixUnavailableException("APISIX Admin API unreachable: " + e.getClass().getSimpleName(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ApisixUnavailableException("interrupted while calling the APISIX Admin API", e);
    }
    int status = r.statusCode();
    if (status / 100 == 2 || (status == 404 && !"PUT".equals(method))) {
      return r;
    }
    throw new ApisixUnavailableException(
        method + " " + kind.path() + " answered " + status + ": " + errorMessage(r.body()));
  }

  private JsonNode read(HttpResponse<String> r) {
    try {
      return json.readTree(r.body());
    } catch (JacksonException e) {
      throw new ApisixUnavailableException("APISIX Admin API answer is not JSON", e);
    }
  }

  private String errorMessage(String body) {
    try {
      return json.readTree(body).path("error_msg").asString("");
    } catch (JacksonException e) {
      return "";
    }
  }
}
