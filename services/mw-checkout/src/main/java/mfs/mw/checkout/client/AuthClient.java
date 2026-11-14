package mfs.mw.checkout.client;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import mfs.mw.checkout.config.AuthClientProperties;
import mfs.mw.checkout.config.ServiceCertificateManager;
import mfs.mw.checkout.dto.AuthOtpRequest;
import mfs.mw.checkout.dto.AuthOtpVerification;
import mfs.mw.checkout.dto.ErrorDetail;
import mfs.mw.checkout.dto.OtpSent;
import mfs.mw.checkout.dto.TransportKey;
import mfs.mw.checkout.dto.TxnAuthorisation;
import mfs.mw.checkout.dto.TxnAuthorisationRequest;
import mfs.mw.checkout.exception.ErrorCodes;
import mfs.mw.checkout.exception.MwException;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * mw-auth for the hosted page (ticket 72; contracts {@code api/mw-auth/auth-internal.v1.openapi.yaml},
 * {@code api/mw-auth/auth.v1.openapi.yaml}; spec 9.3, 18.7, D22, D24). The internal API (the checkout
 * OTP, the transaction assertion) over mTLS with this service's OpenBao certificate ({@link
 * ServiceCertificateManager#clientSslContext()}, CN {@code mw-checkout}) and a client-credentials JWT
 * of the same client ({@link ServiceTokenClient}); 2 s timeout, circuit breaker {@code mw-auth}
 * (4xx ignored). mw-auth's refusals (its 4xx error envelope) pass through with their code, status,
 * details and Bangla text; only a 401 {@code MW-AUTH-001} (this service's token) drops the cached
 * token and retries once, so a wrong PIN is never sent twice. Anything else (unreachable, timeout,
 * breaker open, 5xx, unreadable) is {@code MW-SYS-012} (503, retryable; fail closed). The PIN
 * transport key comes from the plain public port, cached {@code pin-key-ttl}; the last copy is kept
 * while mw-auth cannot be asked. Nothing sent or received is logged.
 */
@Component
public class AuthClient {

  static final String BREAKER = "mw-auth";
  /** mw-auth refused this service's own token (expired, unknown): drop it and retry once. */
  static final String TOKEN_REFUSED = "MW-AUTH-001";
  private static final Logger log = LoggerFactory.getLogger(AuthClient.class);

  private record CachedKey(TransportKey key, Instant fetchedAt) {}

  private final AuthClientProperties props;
  private final RestClient internal;
  private final RestClient open;
  private final ServiceTokenClient tokens;
  private final CircuitBreaker breaker;
  private final JsonMapper json;
  private final Clock clock;
  private volatile CachedKey pinKey;

  public AuthClient(
      AuthClientProperties props,
      ServiceTokenClient tokens,
      CircuitBreakerRegistry breakers,
      ObjectProvider<ServiceCertificateManager> certificates,
      JsonMapper json,
      Clock clock) {
    this.props = props;
    HttpClient.Builder http =
        HttpClient.newBuilder().connectTimeout(props.connectTimeout()).followRedirects(HttpClient.Redirect.NEVER);
    ServiceCertificateManager manager = certificates.getIfAvailable();
    if (manager != null) {
      http.sslContext(manager.clientSslContext());
    }
    this.internal = RestClient.builder().baseUrl(props.baseUrl().toString()).requestFactory(factory(http)).build();
    this.open = RestClient.builder().requestFactory(factory(
        HttpClient.newBuilder().connectTimeout(props.connectTimeout()).followRedirects(HttpClient.Redirect.NEVER)))
        .build();
    this.tokens = tokens;
    this.breaker = breakers.circuitBreaker(BREAKER);
    this.json = json;
    this.clock = clock;
  }

  private JdkClientHttpRequestFactory factory(HttpClient.Builder http) {
    JdkClientHttpRequestFactory f = new JdkClientHttpRequestFactory(http.build());
    f.setReadTimeout(props.timeout());
    return f;
  }

  /** A checkout OTP by SMS to {@code msisdn}, bound to the session (purpose CHECKOUT). */
  public OtpSent sendOtp(String msisdn, UUID sessionId, String locale, String amount, String merchant) {
    OtpSent sent = post("otp", "/internal/v1/otp",
        new AuthOtpRequest(msisdn, AuthOtpRequest.CHECKOUT, sessionId.toString(), locale, amount, merchant),
        OtpSent.class);
    if (sent == null || sent.otpExpiresAt() == null) {
      throw unavailable("otp", null);
    }
    return sent;
  }

  /** Checks the session's OTP and spends it; returns only on a match. */
  public void verifyOtp(String msisdn, UUID sessionId, String otp) {
    post("otp.verify", "/internal/v1/otp/verifications",
        new AuthOtpVerification(msisdn, AuthOtpRequest.CHECKOUT, sessionId.toString(), otp), Void.class);
  }

  /** The PIN check and mw-auth's assertion for this transaction (the compact JWS). */
  public String authorise(TxnAuthorisationRequest req) {
    TxnAuthorisation a = post("txn.authorise", "/internal/v1/txn-authorisations", req, TxnAuthorisation.class);
    if (a == null || a.assertion() == null || a.assertion().isBlank()) {
      throw unavailable("txn.authorise", null);
    }
    return a.assertion();
  }

  /** The PIN transport key (cached), or empty while mw-auth cannot give one and none was fetched before. */
  public Optional<TransportKey> pinKey() {
    CachedKey c = pinKey;
    Instant now = clock.instant();
    if (c != null && now.isBefore(c.fetchedAt().plus(props.pinKeyTtl()))) {
      return Optional.of(c.key());
    }
    try {
      TransportKey k = open.get().uri(props.pinKeyUri()).accept(MediaType.APPLICATION_JSON).retrieve()
          .body(TransportKey.class);
      if (k == null || blank(k.keyId()) || blank(k.publicKeyPem())) {
        throw new IllegalStateException("no key in the answer");
      }
      pinKey = new CachedKey(k, now);
      return Optional.of(k);
    } catch (RuntimeException e) {
      log.atWarn().addKeyValue("event", "auth.pin_key.unavailable").addKeyValue("errorClass", e.getClass().getSimpleName())
          .log("PIN transport key could not be fetched from mw-auth");
      return c == null ? Optional.empty() : Optional.of(c.key());
    }
  }

  private <T> T post(String op, String path, Object body, Class<T> type) {
    try {
      return breaker.executeSupplier(() -> withToken(() -> send(path, body, type)));
    } catch (CallNotPermittedException e) {
      throw unavailable(op, e);
    } catch (HttpClientErrorException e) {
      throw refusal(op, e);
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
      if (!TOKEN_REFUSED.equals(code(e))) {
        throw e;
      }
      tokens.invalidate();
      return call.get();
    }
  }

  private <T> T send(String path, Object body, Class<T> type) {
    RestClient.RequestBodySpec req = internal.post().uri(path)
        .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
        .header("Authorization", "Bearer " + tokens.token());
    String correlationId = MDC.get("correlationId");
    if (correlationId != null) {
      req = req.header("X-Correlation-Id", correlationId);
    }
    RestClient.ResponseSpec res = req.body(body).retrieve();
    if (type == Void.class) {
      res.toBodilessEntity();
      return null;
    }
    return res.body(type);
  }

  /** mw-auth's envelope as this service's exception, same code and status; unreadable: 503. */
  private MwException refusal(String op, HttpClientErrorException e) {
    JsonNode env = envelope(e);
    String code = env == null ? null : env.path("code").asString(null);
    if (code == null || !code.startsWith("MW-") || TOKEN_REFUSED.equals(code)) {
      log.atWarn().addKeyValue("event", "auth.call.refused").addKeyValue("op", op)
          .addKeyValue("status", e.getStatusCode().value()).log("mw-auth refused the call; failing closed");
      return unavailable(op, e);
    }
    List<ErrorDetail> details = new ArrayList<>();
    for (JsonNode d : env.path("details")) {
      details.add(new ErrorDetail(d.path("field").asString(null), d.path("issue").asString(null)));
    }
    HttpStatus status = HttpStatus.resolve(e.getStatusCode().value());
    MwException mw = new MwException(code, env.path("message").asString("Refused"))
        .withStatus(status == null ? HttpStatus.BAD_REQUEST : status)
        .withRetryable(env.path("retryable").asBoolean(false))
        .withDetails(details);
    String bn = env.path("messageBn").asString(null);
    return bn == null ? mw : mw.withMessageBn(bn);
  }

  private String code(HttpClientErrorException e) {
    JsonNode env = envelope(e);
    return env == null ? null : env.path("code").asString(null);
  }

  private JsonNode envelope(HttpClientErrorException e) {
    try {
      byte[] b = e.getResponseBodyAsByteArray();
      return b.length == 0 ? null : json.readTree(b);
    } catch (JacksonException ex) {
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
        .withStatus(HttpStatus.SERVICE_UNAVAILABLE)
        .withRetryable(true);
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }
}
