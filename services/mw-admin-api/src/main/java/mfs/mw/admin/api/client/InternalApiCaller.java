package mfs.mw.admin.api.client;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.StringJoiner;
import mfs.mw.admin.api.config.AdminApiProperties;
import mfs.mw.admin.api.exception.QueryUnavailableException;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * GETs on another service's {@code /internal/v1/**} (ticket 36): bearer token of {@link
 * ServiceTokenClient}, the request's correlation id, no redirects, the configured timeouts. A 401
 * drops the cached token and is {@link QueryUnavailableException}, so the caller's retry fetches a
 * fresh one. Runs on the HTTP request's (virtual) thread.
 */
@Component
class InternalApiCaller {

  private final AdminApiProperties props;
  private final JsonMapper json;
  private final ServiceTokenClient tokens;
  private final HttpClient http;

  InternalApiCaller(AdminApiProperties props, JsonMapper json, ServiceTokenClient tokens) {
    this.props = props;
    this.json = json;
    this.tokens = tokens;
    this.http =
        HttpClient.newBuilder().connectTimeout(props.connectTimeout()).followRedirects(HttpClient.Redirect.NEVER).build();
  }

  /** {@code params} with null values left out, form-encoded. */
  static String query(Map<String, ?> params) {
    StringJoiner q = new StringJoiner("&");
    params.forEach(
        (k, v) -> {
          if (v != null) {
            q.add(k + "=" + URLEncoder.encode(String.valueOf(v), StandardCharsets.UTF_8));
          }
        });
    return q.toString();
  }

  HttpResponse<byte[]> get(URI base, String pathAndQuery, String service) {
    String root = base.toString().replaceAll("/+$", "");
    HttpRequest.Builder b =
        HttpRequest.newBuilder(URI.create(root + pathAndQuery))
            .timeout(props.readTimeout())
            .header("Accept", "application/json")
            .header("Authorization", "Bearer " + tokens.token())
            .GET();
    String correlationId = MDC.get("correlationId");
    if (correlationId != null) {
      b.header("X-Correlation-Id", correlationId);
    }
    HttpResponse<byte[]> res;
    try {
      res = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
    } catch (IOException e) {
      throw new QueryUnavailableException(service + " not reachable", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new QueryUnavailableException("interrupted", e);
    }
    if (res.statusCode() == 401) {
      tokens.invalidate();
      throw new QueryUnavailableException(service + " refused the service token", null);
    }
    return res;
  }

  <T> T read(byte[] body, Class<T> type, String service) {
    try {
      return json.readValue(body, type);
    } catch (JacksonException e) {
      throw new QueryUnavailableException(service + " answer unreadable", e);
    }
  }
}
