package mfs.mw.data.export.client;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import mfs.mw.data.export.config.ExportProperties;
import mfs.mw.data.export.exception.ErrorCodes;
import mfs.mw.data.export.exception.MwException;
import mfs.mw.data.export.service.WalletSnapshotSource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads configured wallets from the core REST API (ADR D31): {@code GET /core/v1/accounts/{id}}
 * and {@code GET /core/v1/balance/{id}}. An empty {@code mw.export.core.wallet-ids} makes no call
 * and the snapshot file is the header only, which the warehouse drop accepts. There is no list-all
 * wallets on the core.
 */
@Component
public class HttpWalletSnapshot implements WalletSnapshotSource {

  private final ExportProperties.Core props;
  private final JsonMapper json;
  private final HttpClient http;
  private final RetryRegistry retries;
  private final CircuitBreakerRegistry breakers;

  public HttpWalletSnapshot(ExportProperties properties, JsonMapper json, RetryRegistry retries,
      CircuitBreakerRegistry breakers) {
    this.props = properties.core();
    this.json = json;
    this.retries = retries;
    this.breakers = breakers;
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  }

  @Override
  public List<Line> lines(Instant at) {
    if (props.walletIds().isEmpty()) {
      return List.of();
    }
    List<Line> lines = new ArrayList<>();
    for (String id : props.walletIds()) {
      lines.add(one(id));
    }
    return lines;
  }

  private Line one(String id) {
    return io.github.resilience4j.retry.Retry.decorateSupplier(retries.retry("dw-core"),
        io.github.resilience4j.circuitbreaker.CircuitBreaker.decorateSupplier(breakers.circuitBreaker("dw-core"),
            () -> read(id))).get();
  }

  private Line read(String id) {
    JsonNode account = get("/core/v1/accounts/" + id);
    JsonNode balance = get("/core/v1/balance/" + id);
    return new Line(
        id,
        account.path("type").asString("WALLET"),
        account.path("status").asString("UNKNOWN"),
        balance.path("available").path("valueMinor").asLong(0),
        balance.path("held").path("valueMinor").asLong(0));
  }

  private JsonNode get(String path) {
    HttpRequest req = HttpRequest.newBuilder(URI.create(props.baseUrl().replaceAll("/+$", "") + path))
        .timeout(Duration.ofSeconds(5))
        .header("Accept", "application/json")
        .GET()
        .build();
    try {
      HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
      if (res.statusCode() != 200) {
        throw new MwException(ErrorCodes.SYS_UPSTREAM, "core snapshot answered " + res.statusCode());
      }
      return json.readTree(res.body());
    } catch (IOException e) {
      throw new MwException(ErrorCodes.SYS_UPSTREAM, "core snapshot not reachable", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new MwException(ErrorCodes.SYS_UPSTREAM, "core snapshot interrupted", e);
    }
  }
}
