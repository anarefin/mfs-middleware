package mfs.mw.app.gateway.client;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.net.http.HttpClient;
import java.util.Optional;
import java.util.function.Supplier;
import mfs.mw.app.gateway.config.AuthClientProperties;
import mfs.mw.app.gateway.config.ServiceCertificateManager;
import mfs.mw.app.gateway.dto.BoundDevice;
import mfs.mw.app.gateway.dto.ChannelPinKey;
import mfs.mw.app.gateway.dto.ErrorEnvelope;
import mfs.mw.app.gateway.dto.LiveSession;
import mfs.mw.app.gateway.dto.PinKey;
import mfs.mw.app.gateway.dto.TxnAuthorisation;
import mfs.mw.app.gateway.dto.TxnAuthorisationRequest;
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
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * mw-auth (tickets 68, 72; contracts {@code api/mw-auth/auth-internal.v1.openapi.yaml} and {@code
 * auth.v1.openapi.yaml}, spec 18.7, D22, D24).
 *
 * <ul>
 *   <li>Only the internal port, over mTLS with this service's Vault certificate ({@link
 *       ServiceCertificateManager#clientSslContext()}, CN {@code mw-app-gateway}) and a
 *       client-credentials JWT of the same client ({@link ServiceTokenClient}): the device gate's
 *       {@link #device} and {@link #session}, the transaction assertion {@link #authorise} and the
 *       PIN transport key {@link #pinKey} (security review of ticket 72: a {@code PIN_TRANSPORT}
 *       key only, never from the plain public port).
 * </ul>
 *
 * Every call has a 2 s timeout ({@code mw.app-gateway.auth.timeout}) and goes through the circuit
 * breaker {@code mw-auth}. For the lookups a 404 is an answer (no bound device, no live session);
 * for {@link #authorise} mw-auth's 4xx envelope is the answer and passes through as it is (code,
 * status, messages, details: a wrong PIN 401 MW-AUTH-040, a lock 423 MW-AUTH-041/042, ...).
 * Everything else fails closed with {@code MW-SYS-012} (503, retryable). A 401 {@code MW-AUTH-001}
 * (or one without an envelope: the service token refused) drops the cached token and retries once;
 * any other 401 (a wrong PIN) is never retried, so a PIN is checked once. HTTP/1.1 only (a
 * cleartext h2c upgrade of a POST is refused by some servers). Nothing sent or received is logged.
 */
@Component
public class AuthClient {

  static final String BREAKER = "mw-auth";
  private static final Logger log = LoggerFactory.getLogger(AuthClient.class);
  private static final String TOKEN_REFUSED = "MW-AUTH-001";

  private final RestClient rest;
  private final ServiceTokenClient tokens;
  private final CircuitBreaker breaker;
  private final JsonMapper json;

  public AuthClient(
      AuthClientProperties props,
      ServiceTokenClient tokens,
      CircuitBreakerRegistry breakers,
      ObjectProvider<ServiceCertificateManager> certificates,
      JsonMapper json) {
    HttpClient.Builder http = http(props);
    ServiceCertificateManager manager = certificates.getIfAvailable();
    if (manager != null) {
      http.sslContext(manager.clientSslContext());
    }
    this.rest = RestClient.builder().baseUrl(props.baseUrl().toString()).requestFactory(factory(http, props)).build();
    this.tokens = tokens;
    this.breaker = breakers.circuitBreaker(BREAKER);
    this.json = json;
  }

  private static HttpClient.Builder http(AuthClientProperties props) {
    return HttpClient.newBuilder()
        .connectTimeout(props.connectTimeout())
        .followRedirects(HttpClient.Redirect.NEVER)
        .version(HttpClient.Version.HTTP_1_1);
  }

  private static JdkClientHttpRequestFactory factory(HttpClient.Builder http, AuthClientProperties props) {
    JdkClientHttpRequestFactory f = new JdkClientHttpRequestFactory(http.build());
    f.setReadTimeout(props.timeout());
    return f;
  }

  /** The bound device {@code deviceId} with its wallet and public key; empty when not bound. */
  public Optional<BoundDevice> device(String deviceId) {
    BoundDevice d = lookup("device", "/internal/v1/devices/{deviceId}", BoundDevice.class, deviceId);
    if (d != null && (d.walletId() == null || d.publicKey() == null)) {
      throw unavailable("device", null);
    }
    return Optional.ofNullable(d);
  }

  /**
   * The wallet's live session on the device with the device's current key fingerprint; empty when
   * there is none or the device is not bound to the wallet (any more). mw-auth extends it by asking.
   */
  public Optional<LiveSession> session(String walletId, String deviceId) {
    LiveSession s =
        lookup("session", "/internal/v1/sessions/{walletId}/{deviceId}", LiveSession.class, walletId, deviceId);
    if (s != null && s.deviceKeyFingerprint() == null) {
      throw unavailable("session", null);
    }
    return Optional.ofNullable(s);
  }

  /**
   * mw-auth's assertion for {@code body} (PIN check first); mw-auth's refusal as an {@link
   * MwException} with its code, status, messages and details.
   */
  public TxnAuthorisation authorise(TxnAuthorisationRequest body) {
    String op = "txn-authorisation";
    TxnAuthorisation a;
    try {
      a = breaker.executeSupplier(() -> withToken(() -> post("/internal/v1/txn-authorisations", body)));
    } catch (HttpClientErrorException e) {
      ErrorEnvelope envelope = envelope(e.getResponseBodyAsByteArray());
      if (envelope == null) {
        throw refused(op, e);
      }
      MwException answer =
          new MwException(envelope.code(), envelope.message())
              .withStatus(HttpStatus.valueOf(e.getStatusCode().value()))
              .withRetryable(envelope.retryable())
              .withDetails(envelope.details());
      throw envelope.messageBn() == null ? answer : answer.withMessageBn(envelope.messageBn());
    } catch (CallNotPermittedException | RestClientException e) {
      throw unavailable(op, e);
    }
    if (a == null || a.assertion() == null || a.assertion().isBlank()) {
      throw unavailable(op, null);
    }
    return a;
  }

  /**
   * The newest PIN transport key, over the internal mTLS port with the service token (security
   * review of ticket 72: never from the plain public port); a key of another purpose is refused.
   */
  public PinKey pinKey() {
    ChannelPinKey k = lookup("pin-key", "/internal/v1/pin-key", ChannelPinKey.class);
    if (k == null || !k.isPinTransport()) {
      log.atWarn().addKeyValue("event", "auth.pin_key.refused")
          .addKeyValue("purpose", k == null || k.purpose() == null ? "none" : k.purpose())
          .log("mw-auth answered no PIN_TRANSPORT key; refused");
      throw unavailable("pin-key", null);
    }
    return k.forApps();
  }

  /** The answer, or null on a 404. */
  private <T> T lookup(String op, String path, Class<T> type, Object... vars) {
    try {
      return breaker.executeSupplier(() -> withToken(() -> get(path, type, vars)));
    } catch (HttpClientErrorException.NotFound e) {
      return null;
    } catch (CallNotPermittedException e) {
      throw unavailable(op, e);
    } catch (HttpClientErrorException e) {
      throw refused(op, e);
    } catch (MwException e) {
      throw e;
    } catch (RestClientException e) {
      throw unavailable(op, e);
    }
  }

  /**
   * A 401 MW-AUTH-001 (or one without an envelope) means the cached token is stale (expired, realm
   * reset): fetch a new one, once. Any other 401 is mw-auth's answer.
   */
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

  private <T> T get(String path, Class<T> type, Object... vars) {
    return headers(rest.get().uri(path, vars).header("Authorization", "Bearer " + tokens.token()))
        .retrieve()
        .body(type);
  }

  private TxnAuthorisation post(String path, Object body) {
    return headers(
            rest.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + tokens.token())
                .body(body))
        .retrieve()
        .body(TxnAuthorisation.class);
  }

  private static <S extends RestClient.RequestHeadersSpec<?>> S headers(S spec) {
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
    } catch (JacksonException | IllegalArgumentException e) {
      return null;
    }
  }

  private static MwException refused(String op, HttpClientErrorException e) {
    log.atWarn()
        .addKeyValue("event", "auth.call.refused")
        .addKeyValue("op", op)
        .addKeyValue("status", e.getStatusCode().value())
        .log("mw-auth refused the call; failing closed");
    return unavailable(op, e);
  }

  private static MwException unavailable(String op, Throwable cause) {
    log.atWarn()
        .addKeyValue("event", "auth.call.failed")
        .addKeyValue("op", op)
        .addKeyValue("errorClass", cause == null ? "BadAnswer" : cause.getClass().getSimpleName())
        .log("mw-auth unavailable; failing closed");
    return new MwException(ErrorCodes.SYS_SESSION_STORE_UNAVAILABLE, "Service temporarily unavailable, try again", cause)
        .withStatus(HttpStatus.SERVICE_UNAVAILABLE);
  }
}
