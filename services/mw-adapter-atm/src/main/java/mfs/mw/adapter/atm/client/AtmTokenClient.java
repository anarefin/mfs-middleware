package mfs.mw.adapter.atm.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import mfs.mw.adapter.atm.config.AuthClientProperties;
import mfs.mw.adapter.atm.dto.AtmTokenVerdict;
import mfs.mw.adapter.atm.exception.MwException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The ATM token check at mw-auth (ticket 120b2, FRS FL-11; contract {@code
 * api/mw-auth/auth-internal.v1.openapi.yaml} {@code POST /internal/v1/atm-tokens/verifications}):
 * the MSISDN, amount (BDT) and token typed at the ATM. A 200 spends the token and names the wallet;
 * mw-auth's refusal of the token or the wallet (401 {@code MW-AUTH-010/011/012}, 403 {@code
 * MW-AUTH-043}, 423 {@code MW-AUTH-041/042}) is DECLINED with that code. A 401 {@code MW-AUTH-001}
 * (the service token) is retried once with a new token. Anything else, this client not allowed
 * included, fails closed: {@value #UNAVAILABLE} (503), so the ATM dispenses nothing. Nothing sent
 * or received is logged.
 */
@Component
public class AtmTokenClient {

  public static final String UNAVAILABLE = "MW-SYS-ATM_TOKEN_UNAVAILABLE";
  static final String PATH = "/internal/v1/atm-tokens/verifications";
  private static final Set<String> DECLINES =
      Set.of("MW-AUTH-010", "MW-AUTH-011", "MW-AUTH-012", "MW-AUTH-041", "MW-AUTH-042", "MW-AUTH-043");
  private static final Logger log = LoggerFactory.getLogger(AtmTokenClient.class);

  private final AuthClientProperties props;
  private final ServiceTokenClient tokens;
  private final JsonMapper json;
  private final HttpClient http;

  public AtmTokenClient(AuthClientProperties props, ServiceTokenClient tokens, JsonMapper json, SslBundles bundles) {
    this.props = props;
    this.tokens = tokens;
    this.json = json;
    HttpClient.Builder builder = HttpClient.newBuilder()
        .connectTimeout(props.connectTimeout())
        .followRedirects(HttpClient.Redirect.NEVER)
        .version(HttpClient.Version.HTTP_1_1);
    if (bundles != null && props.sslBundle() != null && !props.sslBundle().isBlank()) {
      builder.sslContext(bundles.getBundle(props.sslBundle()).createSslContext());
    }
    this.http = builder.build();
  }

  public AtmTokenVerdict verify(String msisdn, long amountMinor, String token) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("msisdn", msisdn);
    body.put("amount", amountMinor);
    body.put("currency", "BDT");
    body.put("token", token);
    byte[] bytes = json.writeValueAsBytes(body);
    HttpResponse<byte[]> res = send(bytes);
    if (res.statusCode() == 401 && "MW-AUTH-001".equals(code(res.body()))) {
      tokens.invalidate();
      res = send(bytes);
    }
    if (res.statusCode() == 200) {
      String wallet = read(res.body()).path("walletId").asString("");
      if (wallet.isEmpty()) {
        throw unavailable("mw-auth answered no wallet", null);
      }
      return AtmTokenVerdict.accepted(wallet);
    }
    String code = code(res.body());
    if (res.statusCode() >= 400 && res.statusCode() < 500 && code != null && DECLINES.contains(code)) {
      return AtmTokenVerdict.declined(code);
    }
    log.atWarn()
        .addKeyValue("event", "atm.token_check.failed")
        .addKeyValue("status", res.statusCode())
        .addKeyValue("errorCode", code == null ? "none" : code)
        .log("mw-auth ATM token check failed; failing closed");
    throw unavailable("mw-auth answered " + res.statusCode(), null);
  }

  private HttpResponse<byte[]> send(byte[] body) {
    String correlation = MDC.get("correlationId");
    HttpRequest req = HttpRequest.newBuilder(URI.create(props.baseUrl().replaceAll("/+$", "") + PATH))
        .timeout(props.readTimeout())
        .header("Content-Type", "application/json")
        .header("Accept", "application/json")
        .header("Authorization", "Bearer " + tokens.token())
        .header("X-Correlation-Id", correlation == null ? UUID.randomUUID().toString() : correlation)
        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
        .build();
    try {
      return http.send(req, HttpResponse.BodyHandlers.ofByteArray());
    } catch (IOException e) {
      throw unavailable("mw-auth not reachable", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw unavailable("mw-auth call interrupted", e);
    }
  }

  private JsonNode read(byte[] body) {
    try {
      return json.readTree(body);
    } catch (JacksonException e) {
      throw unavailable("mw-auth answer is not JSON", e);
    }
  }

  private String code(byte[] body) {
    if (body == null || body.length == 0) {
      return null;
    }
    try {
      String c = json.readTree(body).path("code").asString("");
      return c.isEmpty() ? null : c;
    } catch (JacksonException e) {
      return null;
    }
  }

  private static MwException unavailable(String message, Throwable cause) {
    return new MwException(UNAVAILABLE, message, cause).withStatus(HttpStatus.SERVICE_UNAVAILABLE).withRetryable(true);
  }
}
