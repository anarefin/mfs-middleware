package mfs.mw.ussd.client;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.net.http.HttpClient;
import java.util.function.Supplier;
import mfs.mw.ussd.config.AuthClientProperties;
import mfs.mw.ussd.config.ServiceCertificateManager;
import mfs.mw.ussd.dto.ChannelPinKey;
import mfs.mw.ussd.dto.ErrorEnvelope;
import mfs.mw.ussd.dto.PinKey;
import mfs.mw.ussd.dto.TxnAuthorisation;
import mfs.mw.ussd.dto.TxnAuthorisationRequest;
import mfs.mw.ussd.exception.AuthRefusedException;
import mfs.mw.ussd.exception.ErrorCodes;
import mfs.mw.ussd.exception.MwException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * mw-auth (ticket 72; contracts {@code api/mw-auth/auth-internal.v1.openapi.yaml} and {@code
 * auth.v1.openapi.yaml}, spec 18.7, D22, D24; modelled on mw-app-gateway's copy).
 *
 * <ul>
 *   <li>{@link #authorise}: {@code POST /internal/v1/txn-authorisations} on the internal port over
 *       mTLS with this service's OpenBao certificate ({@link
 *       ServiceCertificateManager#clientSslContext()}, CN {@code mw-ussd}) and a client-credentials
 *       JWT of the same client ({@link ServiceTokenClient}).
 *   <li>{@link #pinKey}: {@code GET /internal/v1/pin-key} on the same internal port, mTLS and token
 *       (security review of ticket 72: never the plain public port; a {@code PIN_TRANSPORT} key only).
 * </ul>
 *
 * Every call has a 2 s timeout and goes through the circuit breaker {@code mw-auth}. mw-auth's error
 * envelope on a 4xx (and {@code MW-SYS-051}, the PIN block refused by mw-crypto) is an answer:
 * {@link AuthRefusedException}. Everything else fails closed with {@code MW-SYS-012} (503,
 * retryable). A 401 {@code MW-AUTH-001} (the service token refused) drops the cached token and
 * retries once; any other 401 (a wrong PIN, MW-AUTH-040) is never retried, so a PIN is checked once.
 * Nothing sent or received is logged (the PIN block and the assertion are secrets).
 */
@Component
public class AuthClient {

  static final String BREAKER = "mw-auth";
  private static final Logger log = LoggerFactory.getLogger(AuthClient.class);
  private static final String TOKEN_REFUSED = "MW-AUTH-001";

  private final RestClient internal;
  private final ServiceTokenClient tokens;
  private final CircuitBreaker breaker;
  private final JsonMapper json;

  public AuthClient(
      AuthClientProperties props,
      ServiceTokenClient tokens,
      CircuitBreakerRegistry breakers,
      ObjectProvider<ServiceCertificateManager> certificates,
      JsonMapper json) {
    HttpClient.Builder http =
        HttpClient.newBuilder().connectTimeout(props.connectTimeout()).followRedirects(HttpClient.Redirect.NEVER)
            // HTTP/1.1: a cleartext h2c upgrade of a POST with a body is refused by some servers (tests run plain)
            .version(HttpClient.Version.HTTP_1_1);
    ServiceCertificateManager manager = certificates.getIfAvailable();
    if (manager != null) {
      http.sslContext(manager.clientSslContext());
    }
    this.internal = RestClient.builder().baseUrl(props.baseUrl().toString()).requestFactory(factory(http, props)).build();
    this.tokens = tokens;
    this.breaker = breakers.circuitBreaker(BREAKER);
    this.json = json;
  }

  private static JdkClientHttpRequestFactory factory(HttpClient.Builder http, AuthClientProperties props) {
    JdkClientHttpRequestFactory f = new JdkClientHttpRequestFactory(http.build());
    f.setReadTimeout(props.timeout());
    return f;
  }

  /**
   * The assertion for {@code body}; {@link AuthRefusedException} with mw-auth's envelope when it
   * refuses (wrong PIN, locks, no PIN, idempotency conflict).
   */
  public TxnAuthorisation authorise(TxnAuthorisationRequest body) {
    TxnAuthorisation a = call("txn-authorisation", () -> withToken(() -> post(body)));
    if (a == null || a.assertion() == null || a.assertion().isBlank()) {
      throw unavailable("txn-authorisation", null);
    }
    return a;
  }

  /**
   * The newest PIN transport key, over the internal mTLS port with the service token (security
   * review of ticket 72: never from the plain public port); a key of another purpose is refused.
   * Any refusal is an outage here ({@code MW-SYS-012}), not an answer.
   */
  public PinKey pinKey() {
    ChannelPinKey k;
    try {
      k = call("pin-key", () -> withToken(() -> request(
              internal.get().uri("/internal/v1/pin-key").header("Authorization", "Bearer " + tokens.token()))
          .retrieve().body(ChannelPinKey.class)));
    } catch (AuthRefusedException e) {
      throw unavailable("pin-key", e);
    }
    if (k == null || !k.isPinTransport()) {
      log.atWarn().addKeyValue("event", "auth.pin_key.refused")
          .addKeyValue("purpose", k == null || k.purpose() == null ? "none" : k.purpose())
          .log("mw-auth answered no PIN_TRANSPORT key; refused");
      throw unavailable("pin-key", null);
    }
    return k.toPinKey();
  }

  private <T> T call(String op, Supplier<T> call) {
    try {
      return breaker.executeSupplier(call);
    } catch (CallNotPermittedException e) {
      throw unavailable(op, e);
    } catch (RestClientResponseException e) {
      ErrorEnvelope envelope = envelope(e.getResponseBodyAsByteArray());
      boolean answer =
          envelope != null
              && (e instanceof HttpClientErrorException || ErrorCodes.SYS_CRYPTO_REFUSED.equals(envelope.code()));
      if (answer) {
        throw new AuthRefusedException(e.getStatusCode().value(), envelope);
      }
      log.atWarn()
          .addKeyValue("event", "auth.call.refused")
          .addKeyValue("op", op)
          .addKeyValue("status", e.getStatusCode().value())
          .log("mw-auth refused the call; failing closed");
      throw unavailable(op, e);
    } catch (MwException e) {
      throw e;
    } catch (RestClientException e) {
      throw unavailable(op, e);
    }
  }

  /** A 401 MW-AUTH-001 means the cached token is stale (expired, realm reset): fetch a new one, once. */
  private <T> T withToken(Supplier<T> call) {
    try {
      return call.get();
    } catch (HttpClientErrorException.Unauthorized e) {
      ErrorEnvelope envelope = envelope(e.getResponseBodyAsByteArray());
      if (envelope != null && !TOKEN_REFUSED.equals(envelope.code())) {
        throw e;
      }
      tokens.invalidate();
      return call.get();
    }
  }

  private TxnAuthorisation post(TxnAuthorisationRequest body) {
    return request(
            internal.post()
                .uri("/internal/v1/txn-authorisations")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + tokens.token())
                .body(body))
        .retrieve()
        .body(TxnAuthorisation.class);
  }

  private static <S extends RestClient.RequestHeadersSpec<?>> S request(S spec) {
    spec.accept(MediaType.APPLICATION_JSON);
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
      return e == null || e.code() == null || e.code().isBlank() ? null : e;
    } catch (JacksonException e) {
      return null;
    }
  }

  private static MwException unavailable(String op, Throwable cause) {
    log.atWarn()
        .addKeyValue("event", "auth.call.failed")
        .addKeyValue("op", op)
        .addKeyValue("errorClass", cause == null ? "BadAnswer" : cause.getClass().getSimpleName())
        .log("mw-auth unavailable; failing closed");
    return new MwException(ErrorCodes.SYS_AUTH_UNAVAILABLE, "Service temporarily unavailable, try again", cause)
        .withStatus(HttpStatus.SERVICE_UNAVAILABLE);
  }
}
