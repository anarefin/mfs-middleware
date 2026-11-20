package mfs.mw.auth.client;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.net.http.HttpClient;
import java.util.Map;
import java.util.UUID;
import mfs.mw.auth.config.CoreClientProperties;
import mfs.mw.auth.dto.CryptoTokens;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The core wallet API for mw-auth (ADR D31, ticket 95b; contract {@code
 * api/mw-core-mock/core-wallet.v1.openapi.yaml}): the PIN reset's KYC factors check {@code POST
 * /core/v1/customers/{walletId}/verify-factors}, which replaced the Kafka command {@code
 * VerifyCustomerFactors}. The factors go only as TOKENISE_NID hashes. The service token is this
 * service's realm {@code services} token (audience {@code mw-core-mock} besides {@code mw-crypto}),
 * renewed once on a 401 {@code MW-AUTH-001}. The check changes nothing at the core and is idempotent by
 * txnId, so a 5xx, 408, 429, timeout or unreachable core is asked again ({@code attempts}, {@code
 * backoff}) and then answered {@link Outcome#UNKNOWN}. The MDC {@code correlationId} goes out as
 * {@code X-Correlation-Id}. Breaker {@code core}. Runs off the request thread
 * ({@code service/PinResetService}); never throws; nothing sent or received is logged.
 */
@Component
public class CoreClient {

  static final String BREAKER = "core";
  static final String TOKEN_REFUSED = "MW-AUTH-001";
  static final String CORE_PREFIX = "MW-CORE-";

  private static final Logger log = LoggerFactory.getLogger(CoreClient.class);

  /** What the core said about the factors. */
  public enum Outcome {
    VERIFIED,
    REJECTED,
    UNKNOWN
  }

  /** The outcome and, for a refusal, the core's reason ({@code FACTORS_MISMATCH}, {@code WALLET_NOT_ACTIVE}, ...). */
  public record FactorsAnswer(Outcome outcome, String reason) {}

  private record Answer(int status, JsonNode body) {}

  private final RestClient http;
  private final ServiceTokenClient tokens;
  private final CircuitBreaker breaker;
  private final CoreClientProperties props;
  private final JsonMapper json;

  public CoreClient(CoreClientProperties props, ServiceTokenClient tokens, CircuitBreakerRegistry breakers, JsonMapper json) {
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
        HttpClient.newBuilder().connectTimeout(props.connectTimeout()).followRedirects(HttpClient.Redirect.NEVER).build());
    factory.setReadTimeout(props.timeout());
    this.http = RestClient.builder().baseUrl(props.baseUrl().toString()).requestFactory(factory).build();
    this.tokens = tokens;
    this.breaker = breakers.circuitBreaker(BREAKER);
    this.props = props;
    this.json = json;
  }

  /** Asks the core whether the factors match the wallet's KYC record. */
  public FactorsAnswer verifyFactors(String walletId, String txnId, UUID resetId, CryptoTokens factors) {
    Map<String, Object> body = Map.of(
        "txnId", txnId,
        "resetId", resetId.toString(),
        "factors", Map.of("nidLast4Token", factors.tokens().get(0), "dateOfBirthToken", factors.tokens().get(1),
            "tokenKid", factors.kid()));
    String cause = "none";
    for (int attempt = 1; attempt <= props.attempts(); attempt++) {
      if (attempt > 1) {
        pause();
      }
      try {
        Answer a = breaker.executeSupplier(() -> send(walletId, body));
        if (a.status() >= 200 && a.status() < 300) {
          return new FactorsAnswer(Outcome.VERIFIED, null);
        }
        String code = a.body() == null ? null : a.body().path("code").asString(null);
        if (a.status() == 422 && code != null && code.startsWith(CORE_PREFIX)) {
          return new FactorsAnswer(Outcome.REJECTED, code.substring(CORE_PREFIX.length()));
        }
        if (a.status() < 500 && a.status() != 408 && a.status() != 429) {
          // 400, 403, 409, a refused token: not the core's decision and not worth asking again
          cause = "status " + a.status();
          break;
        }
        cause = "status " + a.status();
      } catch (RuntimeException e) {
        // timeout, connection, breaker open, token endpoint down
        cause = e.getClass().getSimpleName();
      }
    }
    log.atWarn()
        .addKeyValue("event", "auth.core.factors_unknown")
        .addKeyValue("txnId", txnId)
        .addKeyValue("errorClass", cause)
        .log("The core gave no definite answer to a PIN reset's factors check");
    return new FactorsAnswer(Outcome.UNKNOWN, null);
  }

  /** One exchange; a 401 MW-AUTH-001 renews the token and repeats it once. The wallet id is one path segment. */
  private Answer send(String walletId, Object body) {
    Answer a = exchange(walletId, body);
    if (a.status() == 401 && a.body() != null && TOKEN_REFUSED.equals(a.body().path("code").asString(null))) {
      tokens.invalidate();
      a = exchange(walletId, body);
    }
    if (a.status() >= 500) {
      throw new IllegalStateException("core answered " + a.status());
    }
    return a;
  }

  private Answer exchange(String walletId, Object body) {
    RestClient.RequestBodySpec req = http.post().uri("/core/v1/customers/{walletId}/verify-factors", walletId)
        .accept(MediaType.APPLICATION_JSON)
        .header("Authorization", "Bearer " + tokens.token())
        .contentType(MediaType.APPLICATION_JSON);
    String correlationId = MDC.get("correlationId");
    if (correlationId != null) {
      req = req.header("X-Correlation-Id", correlationId);
    }
    return req.body(body)
        .exchange((request, res) -> new Answer(res.getStatusCode().value(), read(res.getBody().readAllBytes())));
  }

  private JsonNode read(byte[] bytes) {
    if (bytes == null || bytes.length == 0) {
      return null;
    }
    try {
      return json.readTree(bytes);
    } catch (JacksonException e) {
      return null;
    }
  }

  private void pause() {
    try {
      Thread.sleep(props.backoff().toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
