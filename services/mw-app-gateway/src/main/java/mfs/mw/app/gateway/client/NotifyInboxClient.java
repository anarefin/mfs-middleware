package mfs.mw.app.gateway.client;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import mfs.mw.app.gateway.config.NotifyClientProperties;
import mfs.mw.app.gateway.dto.InboxMessage;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * mw-notify's customer inbox (ticket 107a, {@code contracts/api/mw-notify/notify-internal.v1.openapi.yaml}):
 * {@code GET /internal/v1/inbox} and {@code POST /internal/v1/inbox/{id}/read}, called only for the
 * signed-in wallet's MSISDN. The service token is the same client-credentials token as mw-auth
 * (audience {@code mw-notify} as well). A 401 drops the token and is tried once more.
 */
@Component
public class NotifyInboxClient {

  private final NotifyClientProperties props;
  private final ServiceTokenClient tokens;
  private final JsonMapper json;
  private final HttpClient http;

  public NotifyInboxClient(NotifyClientProperties props, ServiceTokenClient tokens, JsonMapper json) {
    this.props = props;
    this.tokens = tokens;
    this.json = json;
    this.http = HttpClient.newBuilder()
        .connectTimeout(props.connectTimeout())
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
  }

  public InboxMessage.Page page(String msisdn, int limit, int offset) {
    String path = "/internal/v1/inbox?msisdn=" + enc(msisdn) + "&limit=" + limit + "&offset=" + offset;
    return json.readValue(send(request(path).GET().build()), InboxMessage.Page.class);
  }

  public void markRead(String msisdn, String id) {
    String path = "/internal/v1/inbox/" + enc(id) + "/read?msisdn=" + enc(msisdn);
    send(request(path).POST(HttpRequest.BodyPublishers.noBody()).build());
  }

  private HttpRequest.Builder request(String path) {
    String base = props.baseUrl().toString().replaceAll("/+$", "");
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
        .timeout(props.readTimeout())
        .header("Accept", "application/json");
    String correlationId = MDC.get("correlationId");
    if (correlationId != null) {
      b.header("X-Correlation-Id", correlationId);
    }
    return b;
  }

  private byte[] send(HttpRequest request) {
    HttpResponse<byte[]> res = exchange(request);
    if (res.statusCode() == 401) {
      tokens.invalidate();
      res = exchange(request);
    }
    if (res.statusCode() == 404) {
      throw new NotifyNotFoundException();
    }
    byte[] body = res.body() == null ? new byte[0] : res.body();
    if (res.statusCode() >= 200 && res.statusCode() < 300) {
      if (body.length == 0) {
        return "{}".getBytes(StandardCharsets.UTF_8);
      }
      try {
        json.readTree(body);
      } catch (JacksonException e) {
        throw new NotifyUnavailableException("notify inbox answer unreadable", e);
      }
      return body;
    }
    throw new NotifyUnavailableException("notify inbox answered " + res.statusCode(), null);
  }

  private HttpResponse<byte[]> exchange(HttpRequest request) {
    HttpRequest authed = HttpRequest.newBuilder(request, (n, v) -> true)
        .header("Authorization", "Bearer " + tokens.token())
        .build();
    try {
      return http.send(authed, HttpResponse.BodyHandlers.ofByteArray());
    } catch (IOException e) {
      throw new NotifyUnavailableException("notify inbox not reachable", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new NotifyUnavailableException("interrupted", e);
    }
  }

  private static String enc(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
  }
}
