package mfs.mw.e2e.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * HTTPS to the APISIX edge. The dev edge certificate is self-signed (deploy/apisix/apply.sh), so
 * this client trusts any certificate: test code only, never copy it into a service.
 */
public final class Http {

  public static final JsonMapper JSON = JsonMapper.builder().build();

  private static final HttpClient CLIENT = newClient();

  private Http() {}

  /** A response with its body parsed as JSON (a missing node when the body is empty or not JSON). */
  public record Response(int status, JsonNode body, String raw, HttpResponse<String> http) {

    public String header(String name) {
      return http.headers().firstValue(name).orElse(null);
    }

    @Override
    public String toString() {
      return "HTTP " + status + " " + raw;
    }
  }

  public static Response get(URI uri, Map<String, String> headers) {
    return send(base(uri, headers).GET().build());
  }

  public static Response delete(URI uri, Map<String, String> headers) {
    return send(base(uri, headers).DELETE().build());
  }

  public static Response postJson(URI uri, Map<String, String> headers, JsonNode body) {
    return send(
        base(uri, headers)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
            .build());
  }

  /** POST of an exact JSON text (for signed bodies: the bytes sent are the bytes signed). */
  public static Response postRaw(URI uri, Map<String, String> headers, String json) {
    return send(
        base(uri, headers)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
            .build());
  }

  public static Response postForm(URI uri, Map<String, String> form) {
    String encoded =
        form.entrySet().stream()
            .map(e -> enc(e.getKey()) + "=" + enc(e.getValue()))
            .collect(Collectors.joining("&"));
    return send(
        base(uri, Map.of())
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(encoded))
            .build());
  }

  private static HttpRequest.Builder base(URI uri, Map<String, String> headers) {
    HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(40));
    headers.forEach(b::header);
    return b;
  }

  private static Response send(HttpRequest request) {
    try {
      HttpResponse<String> r = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
      return new Response(r.statusCode(), parse(r.body()), r.body(), r);
    } catch (IOException e) {
      throw new UncheckedIOException(request.method() + " " + request.uri() + " failed", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private static JsonNode parse(String body) {
    if (body == null || body.isBlank()) {
      return JSON.missingNode();
    }
    try {
      return JSON.readTree(body);
    } catch (RuntimeException notJson) {
      return JSON.missingNode();
    }
  }

  private static String enc(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }

  private static HttpClient newClient() {
    try {
      TrustManager trustAll =
          new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {}

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {}

            @Override
            public X509Certificate[] getAcceptedIssuers() {
              return new X509Certificate[0];
            }
          };
      SSLContext ssl = SSLContext.getInstance("TLS");
      ssl.init(null, new TrustManager[] {trustAll}, null);
      return HttpClient.newBuilder().sslContext(ssl).connectTimeout(Duration.ofSeconds(5)).build();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }
}
