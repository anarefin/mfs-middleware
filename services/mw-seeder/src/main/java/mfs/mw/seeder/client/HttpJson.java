package mfs.mw.seeder.client;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import mfs.mw.seeder.exception.RemoteCallException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The seeder's HTTP: JSON in and out over the JDK client, no redirects. {@code call} names the
 * request in errors and logs as method + route template (never a raw URI with ids, a body or a
 * credential). No answer is a retryable {@link RemoteCallException} with status 0.
 */
public final class HttpJson {

  /** One answer: status, raw body, the {@code Location} header if any. */
  public record Response(int status, String body, Optional<String> location) {}

  private final HttpClient http;
  private final JsonMapper json;
  private final Duration readTimeout;

  public HttpJson(Duration connectTimeout, Duration readTimeout, JsonMapper json) {
    this.http = HttpClient.newBuilder().connectTimeout(connectTimeout).followRedirects(HttpClient.Redirect.NEVER).build();
    this.json = json;
    this.readTimeout = readTimeout;
  }

  public JsonMapper json() {
    return json;
  }

  public Response get(String call, URI uri, Map<String, String> headers) {
    return send(call, request(uri, headers).GET());
  }

  public Response sendJson(String call, String method, URI uri, Map<String, String> headers, Object body) {
    HttpRequest.BodyPublisher pub =
        body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body));
    return send(call, request(uri, headers).header("Content-Type", "application/json").method(method, pub));
  }

  public Response postForm(String call, URI uri, Map<String, String> headers, Map<String, String> form) {
    StringJoiner sj = new StringJoiner("&");
    form.forEach((k, v) -> sj.add(enc(k) + "=" + enc(v)));
    return send(
        call,
        request(uri, headers)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(sj.toString())));
  }

  /** The body read as {@code type}; fails the call when it is not that shape. */
  public <T> T read(String call, Response r, Class<T> type) {
    try {
      return json.readValue(r.body(), type);
    } catch (RuntimeException e) {
      throw new RemoteCallException(r.status(), call + " answered a body that is not a " + type.getSimpleName());
    }
  }

  public JsonNode tree(String call, Response r) {
    return read(call, r, JsonNode.class);
  }

  /** Fails the call unless it answered one of {@code ok}. */
  public static Response expect(String call, Response r, int... ok) {
    for (int s : ok) {
      if (r.status() == s) {
        return r;
      }
    }
    throw new RemoteCallException(r.status(), call + " answered " + r.status() + errorCode(r));
  }

  private HttpRequest.Builder request(URI uri, Map<String, String> headers) {
    HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(readTimeout).header("Accept", "application/json");
    headers.forEach(b::header);
    return b;
  }

  private Response send(String call, HttpRequest.Builder b) {
    try {
      HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      return new Response(res.statusCode(), res.body(), res.headers().firstValue("Location"));
    } catch (IOException e) {
      throw RemoteCallException.unreachable(call, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw RemoteCallException.unreachable(call, e);
    }
  }

  /** The middleware envelope's {@code code} (no message: it may echo input), if the body has one. */
  private static String errorCode(Response r) {
    String body = r.body() == null ? "" : r.body();
    int i = body.indexOf("\"code\"");
    if (i < 0) {
      return "";
    }
    java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"code\"\\s*:\\s*\"(MW-[A-Z0-9_-]{1,40})\"").matcher(body);
    return m.find() ? " " + m.group(1) : "";
  }

  private static String enc(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }
}
