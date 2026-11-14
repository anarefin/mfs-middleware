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
import mfs.mw.app.gateway.dto.LiveSession;
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

/**
 * mw-auth's internal API (ticket 68; contract {@code api/mw-auth/auth-internal.v1.openapi.yaml},
 * spec 18.7, D24): over mTLS with this service's OpenBao certificate ({@link
 * ServiceCertificateManager#clientSslContext()}, CN {@code mw-app-gateway}) and a client-credentials
 * JWT of the same client ({@link ServiceTokenClient}). Every call has a 2 s timeout ({@code
 * mw.app-gateway.auth.timeout}) and goes through the circuit breaker {@code mw-auth}. A 404 is an
 * answer (no bound device, no live session); everything else that is not 200 fails closed with
 * {@code MW-SYS-012} (503, retryable: the session store cannot be asked); a 401 drops the cached
 * token and retries once. Nothing sent or received is logged.
 */
@Component
public class AuthClient {

  static final String BREAKER = "mw-auth";
  private static final Logger log = LoggerFactory.getLogger(AuthClient.class);

  private final RestClient rest;
  private final ServiceTokenClient tokens;
  private final CircuitBreaker breaker;

  public AuthClient(
      AuthClientProperties props,
      ServiceTokenClient tokens,
      CircuitBreakerRegistry breakers,
      ObjectProvider<ServiceCertificateManager> certificates) {
    HttpClient.Builder http =
        HttpClient.newBuilder().connectTimeout(props.connectTimeout()).followRedirects(HttpClient.Redirect.NEVER);
    ServiceCertificateManager manager = certificates.getIfAvailable();
    if (manager != null) {
      http.sslContext(manager.clientSslContext());
    }
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http.build());
    factory.setReadTimeout(props.timeout());
    this.rest = RestClient.builder().baseUrl(props.baseUrl().toString()).requestFactory(factory).build();
    this.tokens = tokens;
    this.breaker = breakers.circuitBreaker(BREAKER);
  }

  /** The bound device {@code deviceId} with its wallet and public key; empty when not bound. */
  public Optional<BoundDevice> device(String deviceId) {
    BoundDevice d = call("device", "/internal/v1/devices/{deviceId}", BoundDevice.class, deviceId);
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
    LiveSession s = call("session", "/internal/v1/sessions/{walletId}/{deviceId}", LiveSession.class, walletId, deviceId);
    if (s != null && s.deviceKeyFingerprint() == null) {
      throw unavailable("session", null);
    }
    return Optional.ofNullable(s);
  }

  /** The answer, or null on a 404. */
  private <T> T call(String op, String path, Class<T> type, Object... vars) {
    try {
      return breaker.executeSupplier(() -> withToken(() -> get(path, type, vars)));
    } catch (HttpClientErrorException.NotFound e) {
      return null;
    } catch (CallNotPermittedException e) {
      throw unavailable(op, e);
    } catch (HttpClientErrorException e) {
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

  /** A 401 means the cached token is stale (expired, realm reset): fetch a new one, once. */
  private <T> T withToken(Supplier<T> call) {
    try {
      return call.get();
    } catch (HttpClientErrorException.Unauthorized e) {
      tokens.invalidate();
      return call.get();
    }
  }

  private <T> T get(String path, Class<T> type, Object... vars) {
    RestClient.RequestHeadersSpec<?> req =
        rest.get()
            .uri(path, vars)
            .accept(MediaType.APPLICATION_JSON)
            .header("Authorization", "Bearer " + tokens.token());
    String correlationId = MDC.get("correlationId");
    if (correlationId != null) {
      req = req.header("X-Correlation-Id", correlationId);
    }
    return req.retrieve().body(type);
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
