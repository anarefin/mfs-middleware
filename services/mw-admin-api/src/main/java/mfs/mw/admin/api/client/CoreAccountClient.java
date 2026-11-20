package mfs.mw.admin.api.client;

import java.net.http.HttpClient;
import java.util.LinkedHashMap;
import java.util.Map;
import mfs.mw.admin.api.config.CoreClientProperties;
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
 * The core wallet API for mw-admin-api (ADR D31, ticket 95b; contract {@code
 * api/mw-core-mock/core-wallet.v1.openapi.yaml}): an approved ops unlock {@code POST
 * /core/v1/accounts/{walletId}/unlock}, which replaced the Kafka command {@code UnlockWallet}. The
 * service token is this service's realm {@code services} token (audience {@code mw-core-mock} besides
 * mw-orchestrator, mw-audit-dw and mw-crypto), renewed once on a 401 {@code MW-AUTH-001}. The unlock is
 * idempotent by txnId (the change request's {@code unlockTxnId}), so a 5xx, 408, 429, timeout or
 * unreachable core is asked again ({@code attempts}, {@code backoff}) and then answered {@link
 * Outcome#UNKNOWN}. Never throws; nothing sent or received is logged.
 */
@Component
public class CoreAccountClient {

  static final String TOKEN_REFUSED = "MW-AUTH-001";
  static final String CORE_PREFIX = "MW-CORE-";

  private static final Logger log = LoggerFactory.getLogger(CoreAccountClient.class);

  /** What the core did with the unlock. */
  public enum Outcome {
    UNLOCKED,
    REFUSED,
    UNKNOWN
  }

  /** The outcome and, for a refusal, the core's reason ({@code WALLET_NOT_LOCKED}, {@code WALLET_NOT_FOUND}). */
  public record UnlockAnswer(Outcome outcome, String reason) {}

  private record Answer(int status, JsonNode body) {}

  private final RestClient http;
  private final ServiceTokenClient tokens;
  private final CoreClientProperties props;
  private final JsonMapper json;

  public CoreAccountClient(CoreClientProperties props, ServiceTokenClient tokens, JsonMapper json) {
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
        HttpClient.newBuilder().connectTimeout(props.connectTimeout()).followRedirects(HttpClient.Redirect.NEVER).build());
    factory.setReadTimeout(props.timeout());
    this.http = RestClient.builder().baseUrl(props.baseUrl().toString()).requestFactory(factory).build();
    this.tokens = tokens;
    this.props = props;
    this.json = json;
  }

  /** Asks the core to lift its lock of {@code walletId}. */
  public UnlockAnswer unlock(String walletId, String txnId, String changeRequestId, String makerId, String checkerId) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("txnId", txnId);
    body.put("changeRequestId", changeRequestId);
    body.put("makerId", makerId);
    body.put("checkerId", checkerId);
    String cause = "none";
    for (int attempt = 1; attempt <= props.attempts(); attempt++) {
      if (attempt > 1) {
        pause();
      }
      try {
        Answer a = send(walletId, body);
        if (a.status() >= 200 && a.status() < 300) {
          return new UnlockAnswer(Outcome.UNLOCKED, null);
        }
        String code = a.body() == null ? null : a.body().path("code").asString(null);
        if (a.status() == 422 && code != null && code.startsWith(CORE_PREFIX)) {
          return new UnlockAnswer(Outcome.REFUSED, code.substring(CORE_PREFIX.length()));
        }
        cause = "status " + a.status();
        if (a.status() < 500 && a.status() != 408 && a.status() != 429) {
          // 400, 403, 409, a refused token: not the core's decision and not worth asking again
          break;
        }
      } catch (RuntimeException e) {
        // timeout, connection, token endpoint down
        cause = e.getClass().getSimpleName();
      }
    }
    log.atWarn()
        .addKeyValue("event", "admin.core.unlock_unknown")
        .addKeyValue("txnId", txnId)
        .addKeyValue("errorClass", cause)
        .log("The core gave no definite answer to an approved unlock");
    return new UnlockAnswer(Outcome.UNKNOWN, null);
  }

  /** One exchange; a 401 MW-AUTH-001 renews the token and repeats it once. The wallet id is one path segment. */
  private Answer send(String walletId, Object body) {
    Answer a = exchange(walletId, body);
    if (a.status() == 401 && a.body() != null && TOKEN_REFUSED.equals(a.body().path("code").asString(null))) {
      tokens.invalidate();
      a = exchange(walletId, body);
    }
    return a;
  }

  private Answer exchange(String walletId, Object body) {
    RestClient.RequestBodySpec req = http.post().uri("/core/v1/accounts/{walletId}/unlock", walletId)
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
