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
import java.time.Instant;
import mfs.mw.app.gateway.config.CoreClientProperties;
import mfs.mw.app.gateway.dto.Amount;
import mfs.mw.app.gateway.dto.CoreAccount;
import mfs.mw.app.gateway.dto.CoreLimits;
import mfs.mw.app.gateway.dto.CoreStatement;
import mfs.mw.app.gateway.exception.MwException;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The core wallet reads mw-app-gateway makes itself (ticket 107b, ADR D31): WAL-01 {@code GET
 * /core/v1/accounts/{msisdn}}, WAL-05 {@code POST /core/v1/limits/check} and WAL-12 {@code GET
 * /core/v1/statements}. The wallet is always the signed-in MSISDN. Idempotent reads: Resilience4j
 * instance {@code core} retries them and breaks the circuit on {@link CoreUnavailableException}
 * only. A 401 drops the cached service token and is tried once more. Nothing sent or received is
 * logged.
 */
@Component
public class CoreWalletClient {

  private final CoreClientProperties props;
  private final ServiceTokenClient tokens;
  private final JsonMapper json;
  private final HttpClient http;

  public CoreWalletClient(CoreClientProperties props, ServiceTokenClient tokens, JsonMapper json) {
    this.props = props;
    this.tokens = tokens;
    this.json = json;
    this.http = HttpClient.newBuilder()
        .connectTimeout(props.connectTimeout())
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
  }

  /** WAL-01. */
  @Retry(name = "core")
  @CircuitBreaker(name = "core")
  public CoreAccount account(String msisdn) {
    return read(send("/core/v1/accounts/" + enc(msisdn), null), CoreAccount.class);
  }

  /** WAL-05. {@code amountMinor} is poisha. */
  @Retry(name = "core")
  @CircuitBreaker(name = "core")
  public CoreLimits limits(String walletId, String txnType, long amountMinor) {
    byte[] body = json.writeValueAsBytes(new LimitsBody(walletId, txnType, new Amount(amountMinor, Amount.BDT)));
    return read(send("/core/v1/limits/check", body), CoreLimits.class);
  }

  /** WAL-12. {@code [from, to)}, page from 0. */
  @Retry(name = "core")
  @CircuitBreaker(name = "core")
  public CoreStatement statement(String walletId, Instant from, Instant to, int limit, int page) {
    String path = "/core/v1/statements?walletId=" + enc(walletId)
        + "&from=" + enc(from.toString())
        + "&to=" + enc(to.toString())
        + "&limit=" + limit
        + "&page=" + page;
    return read(send(path, null), CoreStatement.class);
  }

  private <T> T read(HttpResponse<byte[]> res, Class<T> type) {
    byte[] body = res.body() == null ? new byte[0] : res.body();
    try {
      return json.readValue(body, type);
    } catch (JacksonException e) {
      throw new CoreUnavailableException("core answer unreadable", e);
    }
  }

  private HttpResponse<byte[]> send(String pathAndQuery, byte[] body) {
    HttpResponse<byte[]> res = exchange(pathAndQuery, body);
    if (res.statusCode() == 401) {
      tokens.invalidate();
      res = exchange(pathAndQuery, body);
    }
    int status = res.statusCode();
    if (status >= 200 && status < 300) {
      return res;
    }
    byte[] raw = res.body() == null ? new byte[0] : res.body();
    if (status >= 500 || status == 408 || status == 429) {
      throw new CoreUnavailableException("core answered " + status, null);
    }
    throw new CoreRefusedException(status, code(raw));
  }

  private HttpResponse<byte[]> exchange(String pathAndQuery, byte[] body) {
    String token;
    try {
      token = tokens.token();
    } catch (MwException e) {
      throw new CoreUnavailableException("no service token", e);
    }
    String base = props.baseUrl().toString().replaceAll("/+$", "");
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + pathAndQuery))
        .timeout(props.readTimeout())
        .header("Accept", "application/json")
        .header("Authorization", "Bearer " + token);
    String correlationId = MDC.get("correlationId");
    if (correlationId != null) {
      b.header("X-Correlation-Id", correlationId);
    }
    if (body == null) {
      b.GET();
    } else {
      b.header("Content-Type", "application/json");
      b.POST(HttpRequest.BodyPublishers.ofByteArray(body));
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

  private String code(byte[] body) {
    if (body.length == 0) {
      return null;
    }
    try {
      JsonNode tree = json.readTree(body);
      String code = tree.path("code").asString(null);
      return code == null || code.isBlank() ? null : code;
    } catch (JacksonException e) {
      return null;
    }
  }

  private static String enc(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
  }

  /** WAL-05 body (contract {@code LimitsRequest}). */
  private record LimitsBody(String walletId, String txnType, Amount amount) {}
}
