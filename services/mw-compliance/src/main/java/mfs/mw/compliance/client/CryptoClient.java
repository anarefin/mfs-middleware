package mfs.mw.compliance.client;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.function.Supplier;
import mfs.mw.compliance.config.ComplianceProperties;
import mfs.mw.compliance.config.ServiceCertificateManager;
import mfs.mw.compliance.dto.CryptoEnvelope;
import mfs.mw.compliance.dto.CryptoEnvelopeRequest;
import mfs.mw.compliance.dto.CryptoPlaintext;
import mfs.mw.compliance.dto.CryptoTokeniseRequest;
import mfs.mw.compliance.dto.CryptoTokens;
import mfs.mw.compliance.exception.ErrorCodes;
import mfs.mw.compliance.exception.MwException;
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
 * mw-crypto, the HSM gateway (spec 18.7, 18.8, D23, D24; mw-auth's client, copied and trimmed, D14): over mTLS
 * with this service's Vault certificate and a client-credentials JWT ({@link ServiceTokenClient}). Operations:
 * NID tokenisation ({@code TOKENISE_NID}) and envelope encryption under {@code KEK_COLUMN} (FL-02, CMP-05); mw-crypto
 * binds both purposes to mw-compliance ({@code mw.crypto.authz.purposes}). Fails closed: unreachable, timed out,
 * breaker {@code mw-crypto} open or 5xx is {@code MW-SYS-050} (503, retryable), another refusal {@code MW-SYS-051};
 * a 401 drops the cached token and retries once. Nothing sent or received is logged.
 *
 * <p>Ticket 125 (AML feed) adds {@code /internal/v1/hmac/sign} here for the HMAC-SHA256 party tokens.
 */
@Component
public class CryptoClient {

  /** The KEK purpose of encrypted columns (spec 18.8). */
  public static final String KEK_COLUMN = "KEK_COLUMN";

  static final String BREAKER = "mw-crypto";
  private static final Logger log = LoggerFactory.getLogger(CryptoClient.class);

  private final RestClient rest;
  private final ServiceTokenClient tokens;
  private final CircuitBreaker breaker;

  public CryptoClient(
      ComplianceProperties props,
      ServiceTokenClient tokens,
      CircuitBreakerRegistry breakers,
      ObjectProvider<ServiceCertificateManager> certificates) {
    ComplianceProperties.Crypto crypto = props.crypto();
    HttpClient.Builder http =
        HttpClient.newBuilder().connectTimeout(crypto.connectTimeout()).followRedirects(HttpClient.Redirect.NEVER);
    ServiceCertificateManager manager = certificates.getIfAvailable();
    if (manager != null) {
      http.sslContext(manager.clientSslContext());
    }
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http.build());
    factory.setReadTimeout(crypto.timeout());
    this.rest = RestClient.builder().baseUrl(crypto.baseUrl().toString()).requestFactory(factory).build();
    this.tokens = tokens;
    this.breaker = breakers.circuitBreaker(BREAKER);
  }

  /** The deterministic token of one NID (as given: callers normalise) under TOKENISE_NID. */
  public String tokeniseNid(String nid) {
    CryptoTokens t =
        call("tokenise", "/internal/v1/tokens", new CryptoTokeniseRequest("NID", List.of(nid)), CryptoTokens.class);
    if (t == null || t.tokens() == null || t.tokens().size() != 1) {
      throw unavailable("tokenise", null);
    }
    return t.tokens().getFirst();
  }

  /** Encrypts {@code plaintext} under {@code purpose} (a KEK_ purpose), bound to {@code aad}. */
  public CryptoEnvelope encrypt(String purpose, byte[] plaintext, byte[] aad) {
    Base64.Encoder b64 = Base64.getEncoder();
    CryptoEnvelope e = call("envelope.encrypt", "/internal/v1/envelope/encrypt",
        new CryptoEnvelopeRequest(purpose, b64.encodeToString(plaintext), aad == null ? null : b64.encodeToString(aad)),
        CryptoEnvelope.class);
    if (e == null || e.kekId() == null || e.ciphertext() == null) {
      throw unavailable("envelope.encrypt", null);
    }
    return e;
  }

  /** Opens an envelope made by {@link #encrypt}. */
  public byte[] decrypt(CryptoEnvelope envelope) {
    CryptoPlaintext p = call("envelope.decrypt", "/internal/v1/envelope/decrypt", envelope, CryptoPlaintext.class);
    if (p == null || p.plaintext() == null) {
      throw unavailable("envelope.decrypt", null);
    }
    return Base64.getDecoder().decode(p.plaintext().getBytes(StandardCharsets.US_ASCII));
  }

  private <T> T call(String op, String path, Object body, Class<T> type) {
    try {
      return breaker.executeSupplier(() -> withToken(() -> post(path, body, type)));
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

  private <T> T withToken(Supplier<T> call) {
    try {
      return call.get();
    } catch (HttpClientErrorException.Unauthorized e) {
      tokens.invalidate();
      return call.get();
    }
  }

  private <T> T post(String path, Object body, Class<T> type) {
    RestClient.RequestBodySpec req = rest.post().uri(path)
        .contentType(MediaType.APPLICATION_JSON)
        .accept(MediaType.APPLICATION_JSON)
        .header("Authorization", "Bearer " + tokens.token());
    String correlationId = MDC.get("correlationId");
    if (correlationId != null) {
      req = req.header("X-Correlation-Id", correlationId);
    }
    return req.body(body).retrieve().body(type);
  }

  private static MwException refused(String op, HttpClientErrorException e) {
    log.atWarn().addKeyValue("event", "crypto.call.refused").addKeyValue("op", op)
        .addKeyValue("status", e.getStatusCode().value()).log("mw-crypto refused the call");
    return new MwException(ErrorCodes.SYS_CRYPTO_REFUSED, "mw-crypto refused " + op, e).withRetryable(false);
  }

  private static MwException unavailable(String op, Throwable cause) {
    log.atWarn().addKeyValue("event", "crypto.call.failed").addKeyValue("op", op)
        .addKeyValue("errorClass", cause == null ? "BadAnswer" : cause.getClass().getSimpleName())
        .log("mw-crypto unavailable; failing closed");
    return new MwException(ErrorCodes.SYS_CRYPTO_UNAVAILABLE, "mw-crypto unavailable: " + op, cause)
        .withStatus(HttpStatus.SERVICE_UNAVAILABLE);
  }
}
