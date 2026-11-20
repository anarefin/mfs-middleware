package mfs.mw.adapter.remit.client;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import mfs.mw.adapter.remit.config.CoreClientProperties;
import mfs.mw.adapter.remit.dto.CoreAccount;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The core wallet read of the beneficiary lookup (ticket 119c, ADR D31): WAL-01 {@code GET
 * /core/v1/accounts/{msisdn}}. A 404 is an unknown wallet (empty); any other refusal, 5xx, timeout or
 * unreadable body is {@link CoreUnavailableException}. An idempotent read: Resilience4j instance {@code
 * core} retries it and breaks the circuit. A 401 drops the cached token and is tried once more. Nothing
 * sent or received is logged.
 */
@Component
public class CoreAccountClient {

  private final CoreClientProperties props;
  private final ServiceTokenClient tokens;
  private final JsonMapper json;
  private final HttpClient http;

  public CoreAccountClient(CoreClientProperties props, ServiceTokenClient tokens, JsonMapper json) {
    this.props = props;
    this.tokens = tokens;
    this.json = json;
    this.http =
        HttpClient.newBuilder().connectTimeout(props.connectTimeout()).followRedirects(HttpClient.Redirect.NEVER).build();
  }

  /** WAL-01: the account of a wallet id or MSISDN, empty when the core knows none. */
  @Retry(name = "core")
  @CircuitBreaker(name = "core")
  public Optional<CoreAccount> account(String msisdn) {
    String path = "/core/v1/accounts/" + URLEncoder.encode(msisdn, StandardCharsets.UTF_8);
    HttpResponse<byte[]> res = exchange(path);
    if (res.statusCode() == 401) {
      tokens.invalidate();
      res = exchange(path);
    }
    int status = res.statusCode();
    if (status == 404) {
      return Optional.empty();
    }
    if (status != 200) {
      throw new CoreUnavailableException("core answered " + status, null);
    }
    try {
      return Optional.of(json.readValue(res.body(), CoreAccount.class));
    } catch (JacksonException e) {
      throw new CoreUnavailableException("core answer unreadable", e);
    }
  }

  private HttpResponse<byte[]> exchange(String path) {
    String token = tokens.token();
    String base = props.baseUrl().toString().replaceAll("/+$", "");
    HttpRequest.Builder b =
        HttpRequest.newBuilder(URI.create(base + path))
            .timeout(props.readTimeout())
            .header("Accept", "application/json")
            .header("Authorization", "Bearer " + token)
            .GET();
    String correlationId = MDC.get("correlationId");
    if (correlationId != null) {
      b.header("X-Correlation-Id", correlationId);
    }
    try {
      return http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
    } catch (IOException e) {
      throw new CoreUnavailableException("core not reachable", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new CoreUnavailableException("interrupted", e);
    }
  }
}
