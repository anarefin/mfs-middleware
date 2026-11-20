package mfs.mw.ussd.client;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import mfs.mw.ussd.config.CoreClientProperties;
import mfs.mw.ussd.exception.CoreUnavailableException;
import mfs.mw.ussd.service.CoreAccounts;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * WAL-01 {@code GET /core/v1/accounts/{msisdn}} with this service's client-credentials JWT
 * (audience {@code mw-core-mock}). A missing account is empty; anything else that is not a 200
 * fails closed. The MSISDN is not logged.
 */
@Component
@ConditionalOnProperty(name = "mw.ussd.core.mode", havingValue = "http", matchIfMissing = true)
public class CoreAccountClient implements CoreAccounts {

  private final CoreClientProperties props;
  private final ServiceTokenClient tokens;
  private final JsonMapper json;
  private final HttpClient http;

  public CoreAccountClient(CoreClientProperties props, ServiceTokenClient tokens, JsonMapper json) {
    this.props = props;
    this.tokens = tokens;
    this.json = json;
    this.http =
        HttpClient.newBuilder()
            .connectTimeout(props.connectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  @Override
  @CircuitBreaker(name = "core")
  public Optional<String> type(String msisdn) {
    String path = "/core/v1/accounts/" + URLEncoder.encode(msisdn, StandardCharsets.UTF_8).replace("+", "%20");
    HttpRequest req =
        HttpRequest.newBuilder(URI.create(props.baseUrl().toString().replaceAll("/+$", "") + path))
            .timeout(props.readTimeout())
            .header("Accept", "application/json")
            .header("Authorization", "Bearer " + tokens.token())
            .GET()
            .build();
    HttpResponse<byte[]> res;
    try {
      res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
    } catch (IOException e) {
      throw new CoreUnavailableException("core account read not reachable", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new CoreUnavailableException("core account read interrupted", e);
    }
    if (res.statusCode() == 404) {
      return Optional.empty();
    }
    if (res.statusCode() < 200 || res.statusCode() >= 300) {
      throw new CoreUnavailableException("core account read answered " + res.statusCode(), null);
    }
    JsonNode body;
    try {
      body = json.readTree(res.body() == null ? new byte[0] : res.body());
    } catch (RuntimeException e) {
      throw new CoreUnavailableException("core account read unreadable", e);
    }
    String type = body.path("type").asString("");
    return type.isBlank() ? Optional.empty() : Optional.of(type);
  }
}
