package mfs.mw.portal.api.client;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.UUID;
import mfs.mw.portal.api.config.OrchestratorClientProperties;
import mfs.mw.portal.api.dto.OrgTxn;
import mfs.mw.portal.api.exception.OrchestratorUnavailableException;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Internal HTTP client of mw-orchestrator's query API ({@code contracts/api/mw-orchestrator/
 * txn-query.v1.openapi.yaml}, ticket 35). Every call carries the caller's {@code orgId}, so the
 * orchestrator only ever returns that org's transactions. Idempotent reads: Resilience4j instance
 * {@code orchestrator} retries and breaks the circuit on {@link OrchestratorUnavailableException}
 * only. Runs on the HTTP request's (virtual) thread.
 */
@Component
public class OrchestratorQueryClient {

  static final String PATH = "/internal/v1/transactions";

  private final OrchestratorClientProperties props;
  private final JsonMapper json;
  private final HttpClient http;

  public OrchestratorQueryClient(OrchestratorClientProperties props, JsonMapper json) {
    this.props = props;
    this.json = json;
    this.http =
        HttpClient.newBuilder()
            .connectTimeout(props.connectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  /** @param filters query filters other than {@code orgId} (null values are left out) */
  @Retry(name = "orchestrator")
  @CircuitBreaker(name = "orchestrator")
  public OrgTxn.Page search(String orgId, Map<String, Object> filters) {
    Map<String, Object> q = new LinkedHashMap<>(filters);
    q.put("orgId", orgId);
    HttpResponse<byte[]> res = send(PATH + "?" + query(q));
    if (res.statusCode() != 200) {
      throw new OrchestratorUnavailableException("orchestrator query answered " + res.statusCode(), null);
    }
    return read(res.body(), OrgTxn.Page.class);
  }

  /** The org's transaction with its timeline; empty when it does not exist or is another org's. */
  @Retry(name = "orchestrator")
  @CircuitBreaker(name = "orchestrator")
  public Optional<OrgTxn.Detail> detail(String orgId, UUID txnId) {
    HttpResponse<byte[]> res = send(PATH + "/" + txnId + "?" + query(Map.of("orgId", orgId)));
    if (res.statusCode() == 404) {
      return Optional.empty();
    }
    if (res.statusCode() != 200) {
      throw new OrchestratorUnavailableException("orchestrator query answered " + res.statusCode(), null);
    }
    return Optional.of(read(res.body(), OrgTxn.Detail.class));
  }

  private static String query(Map<String, Object> params) {
    StringJoiner q = new StringJoiner("&");
    params.forEach(
        (k, v) -> {
          if (v != null) {
            q.add(k + "=" + URLEncoder.encode(String.valueOf(v), StandardCharsets.UTF_8));
          }
        });
    return q.toString();
  }

  private HttpResponse<byte[]> send(String pathAndQuery) {
    String base = props.baseUrl().toString().replaceAll("/+$", "");
    HttpRequest.Builder b =
        HttpRequest.newBuilder(URI.create(base + pathAndQuery))
            .timeout(props.readTimeout())
            .header("Accept", "application/json")
            .GET();
    String correlationId = MDC.get("correlationId");
    if (correlationId != null) {
      b.header("X-Correlation-Id", correlationId);
    }
    try {
      return http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
    } catch (IOException e) {
      throw new OrchestratorUnavailableException("orchestrator not reachable", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new OrchestratorUnavailableException("interrupted", e);
    }
  }

  private <T> T read(byte[] body, Class<T> type) {
    try {
      return json.readValue(body, type);
    } catch (JacksonException e) {
      throw new OrchestratorUnavailableException("orchestrator answer unreadable", e);
    }
  }
}
