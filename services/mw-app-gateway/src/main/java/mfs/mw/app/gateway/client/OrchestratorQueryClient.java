package mfs.mw.app.gateway.client;

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
import java.util.StringJoiner;
import mfs.mw.app.gateway.config.OrchestratorClientProperties;
import mfs.mw.app.gateway.dto.QueryTxnPage;
import mfs.mw.app.gateway.exception.MwException;
import mfs.mw.app.gateway.exception.OrchestratorUnavailableException;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Internal HTTP client of mw-orchestrator's query API ({@code contracts/api/mw-orchestrator/
 * txn-query.v1.openapi.yaml} 1.3.0, ADR D25; copied from mw-portal-api's, D14). Every call carries
 * the signed-in wallet as {@code walletId}: this service is a wallet-scoped caller there, so it can
 * only ever read that wallet's history. An idempotent read: Resilience4j instance {@code
 * orchestrator} retries it and breaks the circuit on {@link OrchestratorUnavailableException} only.
 * Runs on the HTTP request's (virtual) thread. Authenticated with this service's client-credentials
 * token ({@link ServiceTokenClient}); a 401 drops the cached token and asks once more with a fresh
 * one.
 */
@Component
public class OrchestratorQueryClient {

  static final String PATH = "/internal/v1/transactions";
  static final String WALLET_PARAM = "walletId";

  private final OrchestratorClientProperties props;
  private final JsonMapper json;
  private final ServiceTokenClient tokens;
  private final HttpClient http;

  public OrchestratorQueryClient(OrchestratorClientProperties props, JsonMapper json, ServiceTokenClient tokens) {
    this.props = props;
    this.json = json;
    this.tokens = tokens;
    this.http =
        HttpClient.newBuilder()
            .connectTimeout(props.connectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  /**
   * @param walletId the signed-in wallet (from the token, never from the request)
   * @param filters the other query filters (null values are left out; a {@code walletId} here is
   *     ignored)
   */
  @Retry(name = "orchestrator")
  @CircuitBreaker(name = "orchestrator")
  public QueryTxnPage walletHistory(String walletId, Map<String, Object> filters) {
    Map<String, Object> q = new LinkedHashMap<>(filters);
    q.remove(WALLET_PARAM);
    Map<String, Object> params = new LinkedHashMap<>();
    params.put(WALLET_PARAM, walletId);
    params.putAll(q);
    String pathAndQuery = PATH + "?" + query(params);
    HttpResponse<byte[]> res = send(pathAndQuery);
    if (res.statusCode() == 401) {
      // expired or revoked early: once more with a fresh token
      tokens.invalidate();
      res = send(pathAndQuery);
    }
    if (res.statusCode() != 200) {
      throw new OrchestratorUnavailableException("orchestrator query answered " + res.statusCode(), null);
    }
    try {
      return json.readValue(res.body(), QueryTxnPage.class);
    } catch (JacksonException e) {
      throw new OrchestratorUnavailableException("orchestrator answer unreadable", e);
    }
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
    String token;
    try {
      token = tokens.token();
    } catch (MwException e) {
      throw new OrchestratorUnavailableException("no service token", e);
    }
    String base = props.baseUrl().toString().replaceAll("/+$", "");
    HttpRequest.Builder b =
        HttpRequest.newBuilder(URI.create(base + pathAndQuery))
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
      throw new OrchestratorUnavailableException("orchestrator not reachable", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new OrchestratorUnavailableException("interrupted", e);
    }
  }
}
