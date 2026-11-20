package mfs.mw.app.gateway.client;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.net.http.HttpClient;
import java.util.function.Supplier;
import mfs.mw.app.gateway.config.ComplianceClientProperties;
import mfs.mw.app.gateway.config.ServiceCertificateManager;
import mfs.mw.app.gateway.dto.ErrorEnvelope;
import mfs.mw.app.gateway.exception.ErrorCodes;
import mfs.mw.app.gateway.exception.MwException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * mw-compliance's eKYC API (ticket 124b; contract {@code api/mw-compliance/compliance.v1.openapi.yaml}) over mTLS
 * with this service's Vault certificate and its client-credentials token (audience {@code mw-compliance}). Every
 * call names the end user in {@code X-Mw-Subject-Type} / {@code X-Mw-Subject-Id}, taken from the app token by the
 * caller (mw-compliance refuses a scoped caller without them). mw-compliance's envelope (4xx, and a 5xx with one,
 * such as {@code MW-TMO-EC_NID}) passes through as it is; anything else fails closed with {@code MW-SYS-017}
 * (503, retryable). A 401 {@code MW-AUTH-001} (or one without an envelope) drops the cached token and retries
 * once. Circuit breaker {@code mw-compliance}. Nothing sent or received is logged: the bodies carry the NID, the
 * name and the images.
 */
@Component
public class ComplianceClient {

  /** The end user the app token names. */
  public record Subject(String type, String id) {}

  static final String BREAKER = "mw-compliance";
  private static final Logger log = LoggerFactory.getLogger(ComplianceClient.class);
  private static final String TOKEN_REFUSED = "MW-AUTH-001";

  private final RestClient rest;
  private final ServiceTokenClient tokens;
  private final CircuitBreaker breaker;
  private final JsonMapper json;

  public ComplianceClient(
      ComplianceClientProperties props,
      ServiceTokenClient tokens,
      CircuitBreakerRegistry breakers,
      ObjectProvider<ServiceCertificateManager> certificates,
      JsonMapper json) {
    HttpClient.Builder http = HttpClient.newBuilder()
        .connectTimeout(props.connectTimeout())
        .followRedirects(HttpClient.Redirect.NEVER)
        .version(HttpClient.Version.HTTP_1_1);
    ServiceCertificateManager manager = certificates.getIfAvailable();
    if (manager != null) {
      http.sslContext(manager.clientSslContext());
    }
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http.build());
    factory.setReadTimeout(props.timeout());
    this.rest = RestClient.builder().baseUrl(props.baseUrl().toString()).requestFactory(factory).build();
    this.tokens = tokens;
    this.breaker = breakers.circuitBreaker(BREAKER);
    this.json = json;
  }

  /** POST {@code path} with {@code body} (none when null) for {@code subject}; mw-compliance's answer. */
  public JsonNode post(String path, Object body, Subject subject) {
    return call("post", () -> {
      RestClient.RequestBodySpec spec = headers(rest.post().uri(path), subject);
      if (body != null) {
        spec.contentType(MediaType.APPLICATION_JSON).body(body);
      }
      return spec.retrieve().body(JsonNode.class);
    });
  }

  /** GET {@code path} for {@code subject}; mw-compliance's answer. */
  public JsonNode get(String path, Subject subject) {
    return call("get", () -> headers(rest.get().uri(path), subject).retrieve().body(JsonNode.class));
  }

  private JsonNode call(String op, Supplier<JsonNode> request) {
    JsonNode answer;
    try {
      answer = breaker.executeSupplier(() -> withToken(request));
    } catch (HttpClientErrorException | HttpServerErrorException e) {
      ErrorEnvelope envelope = envelope(e.getResponseBodyAsByteArray());
      if (envelope == null) {
        throw unavailable(op, e);
      }
      MwException passed = new MwException(envelope.code(), envelope.message())
          .withStatus(HttpStatus.valueOf(e.getStatusCode().value()))
          .withRetryable(envelope.retryable())
          .withDetails(envelope.details());
      throw envelope.messageBn() == null ? passed : passed.withMessageBn(envelope.messageBn());
    } catch (CallNotPermittedException | RestClientException e) {
      throw unavailable(op, e);
    }
    if (answer == null || !answer.isObject()) {
      throw unavailable(op, null);
    }
    return answer;
  }

  private JsonNode withToken(Supplier<JsonNode> request) {
    try {
      return request.get();
    } catch (HttpClientErrorException.Unauthorized e) {
      ErrorEnvelope envelope = envelope(e.getResponseBodyAsByteArray());
      if (envelope != null && !TOKEN_REFUSED.equals(envelope.code())) {
        throw e;
      }
      tokens.invalidate();
      return request.get();
    }
  }

  private <S extends RestClient.RequestHeadersSpec<?>> S headers(S spec, Subject subject) {
    spec.accept(MediaType.APPLICATION_JSON);
    spec.header("Authorization", "Bearer " + tokens.token());
    spec.header("X-Mw-Subject-Type", subject.type());
    spec.header("X-Mw-Subject-Id", subject.id());
    String correlationId = MDC.get("correlationId");
    if (correlationId != null) {
      spec.header("X-Correlation-Id", correlationId);
    }
    return spec;
  }

  private ErrorEnvelope envelope(byte[] body) {
    if (body == null || body.length == 0) {
      return null;
    }
    try {
      ErrorEnvelope e = json.readValue(body, ErrorEnvelope.class);
      return e == null || e.code() == null || !e.code().startsWith("MW-") ? null : e;
    } catch (JacksonException | IllegalArgumentException e) {
      return null;
    }
  }

  private static MwException unavailable(String op, Throwable cause) {
    log.atWarn()
        .addKeyValue("event", "compliance.call.failed")
        .addKeyValue("op", op)
        .addKeyValue("errorClass", cause == null ? "BadAnswer" : cause.getClass().getSimpleName())
        .log("mw-compliance unavailable; failing closed");
    return new MwException(ErrorCodes.SYS_COMPLIANCE_UNAVAILABLE, "Service temporarily unavailable, try again", cause)
        .withStatus(HttpStatus.SERVICE_UNAVAILABLE);
  }
}
