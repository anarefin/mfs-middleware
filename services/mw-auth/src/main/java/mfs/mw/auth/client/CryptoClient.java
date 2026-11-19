package mfs.mw.auth.client;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import mfs.mw.auth.config.AuthProperties;
import mfs.mw.auth.config.ServiceCertificateManager;
import mfs.mw.auth.dto.CryptoDerivePinRequest;
import mfs.mw.auth.dto.CryptoEnvelope;
import mfs.mw.auth.dto.CryptoJwsSignRequest;
import mfs.mw.auth.dto.CryptoJwsSigned;
import mfs.mw.auth.dto.CryptoPinDerived;
import mfs.mw.auth.dto.CryptoPinVerified;
import mfs.mw.auth.dto.CryptoVerifyPinRequest;
import mfs.mw.auth.dto.EncryptedPin;
import mfs.mw.auth.dto.CryptoEnvelopeRequest;
import mfs.mw.auth.dto.CryptoPlaintext;
import mfs.mw.auth.dto.CryptoTokeniseRequest;
import mfs.mw.auth.dto.CryptoTokens;
import mfs.mw.auth.exception.ErrorCodes;
import mfs.mw.auth.exception.MwException;
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
 * mw-crypto, the HSM gateway (spec 18.7, 18.8, D23, D24): over mTLS with this service's Vault
 * certificate ({@link ServiceCertificateManager#clientSslContext()}, CN {@code mw-auth}) and a
 * client-credentials JWT of the same client ({@link ServiceTokenClient}). Every call has a 2 s
 * timeout ({@code mw.auth.crypto.timeout}) and goes through the circuit breaker {@code mw-crypto}.
 * It fails closed: unreachable, timed out, breaker open or a 5xx is {@code MW-SYS-050} (503,
 * retryable); any other refusal {@code MW-SYS-051}; a 401 drops the cached token and retries once.
 * Nothing sent or received is logged (no payload logging, values are MSISDNs and ciphertexts).
 *
 * <p>Operations: MSISDN tokenisation (the keyed hash of {@code auth.wallet}) and envelope
 * encryption under {@code KEK_COLUMN} (the encrypted MSISDN), ticket 67; PIN verify and derive, the
 * access token signature ({@code JWT_SIGN}, RS256) and the JWK Sets of {@code JWT_SIGN} and {@code
 * PIN_TRANSPORT}, ticket 69. mw-crypto allows mw-auth all of them ({@code mw.crypto.authz}).
 */
@Component
public class CryptoClient {

  /** The KEK purpose of encrypted columns (spec 18.8). */
  public static final String KEK_COLUMN = "KEK_COLUMN";

  static final String BREAKER = "mw-crypto";
  private static final Logger log = LoggerFactory.getLogger(CryptoClient.class);

  /** One MSISDN's token and the TOKENISE_MSISDN key version that made it. */
  public record MsisdnToken(String token, String kid) {
    @Override
    public String toString() {
      return "MsisdnToken[token=****, kid=" + kid + "]";
    }
  }

  private final RestClient rest;
  private final ServiceTokenClient tokens;
  private final CircuitBreaker breaker;

  public CryptoClient(
      AuthProperties props,
      ServiceTokenClient tokens,
      CircuitBreakerRegistry breakers,
      ObjectProvider<ServiceCertificateManager> certificates) {
    AuthProperties.Crypto crypto = props.crypto();
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

  /** The deterministic token of one MSISDN (as given: callers normalise) under TOKENISE_MSISDN. */
  public MsisdnToken tokeniseMsisdn(String msisdn) {
    CryptoTokens t =
        call("tokenise", "/internal/v1/tokens", new CryptoTokeniseRequest("MSISDN", List.of(msisdn)), CryptoTokens.class);
    if (t == null || t.tokens() == null || t.tokens().size() != 1 || t.kid() == null) {
      throw unavailable("tokenise", null);
    }
    return new MsisdnToken(t.tokens().getFirst(), t.kid());
  }

  /**
   * The deterministic tokens of KYC factors under TOKENISE_NID (ticket 70: the self-service reset's
   * factors, each bound to the wallet by the caller), in order, with the key version.
   */
  public CryptoTokens tokeniseNid(List<String> values) {
    CryptoTokens t = call("tokenise", "/internal/v1/tokens", new CryptoTokeniseRequest("NID", values), CryptoTokens.class);
    if (t == null || t.tokens() == null || t.tokens().size() != values.size() || t.kid() == null) {
      throw unavailable("tokenise", null);
    }
    return t;
  }

  /** Encrypts {@code plaintext} under {@code purpose} (a KEK_ purpose), bound to {@code aad}. */
  public CryptoEnvelope encrypt(String purpose, byte[] plaintext, byte[] aad) {
    Base64.Encoder b64 = Base64.getEncoder();
    CryptoEnvelope e =
        call(
            "envelope.encrypt",
            "/internal/v1/envelope/encrypt",
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

  /** Checks a PIN block against the stored HMAC (match or not; a rehash under the ACTIVE version). */
  public CryptoPinVerified verifyPin(String walletId, EncryptedPin pin, String storedHmac, String hmacKeyId) {
    CryptoPinVerified v =
        call("pin.verify", "/internal/v1/pin/verify",
            new CryptoVerifyPinRequest(walletId, pin, storedHmac, hmacKeyId), CryptoPinVerified.class);
    if (v == null) {
      throw unavailable("pin.verify", null);
    }
    return v;
  }

  /** The PIN HMAC to store for a new PIN, or the weak-PIN rule it breaks. */
  public CryptoPinDerived derivePin(String walletId, EncryptedPin pin, Map<String, Object> policyInput) {
    CryptoPinDerived d =
        call("pin.derive", "/internal/v1/pin/derive", new CryptoDerivePinRequest(walletId, pin, policyInput),
            CryptoPinDerived.class);
    if (d == null || (!d.weak() && (d.hmac() == null || d.hmacKeyId() == null))) {
      throw unavailable("pin.derive", null);
    }
    return d;
  }

  /** A compact JWS over {@code claims} by the ACTIVE key of {@code purpose}, with extra header members. */
  public CryptoJwsSigned signJws(String purpose, String alg, Map<String, Object> header, Map<String, Object> claims) {
    CryptoJwsSigned s =
        call("jws.sign", "/internal/v1/jws/sign", new CryptoJwsSignRequest(purpose, alg, header, claims),
            CryptoJwsSigned.class);
    if (s == null || s.jws() == null || s.kid() == null) {
      throw unavailable("jws.sign", null);
    }
    return s;
  }

  /** The JWK Set (JSON) of every key version in use of an asymmetric purpose. */
  @SuppressWarnings("unchecked")
  public Map<String, Object> jwks(String purpose) {
    Map<String, Object> set;
    try {
      set = breaker.executeSupplier(() -> withToken(() -> get("/internal/v1/jwks/" + purpose, Map.class)));
    } catch (CallNotPermittedException e) {
      throw unavailable("jwks", e);
    } catch (HttpClientErrorException e) {
      throw refused("jwks", e);
    } catch (MwException e) {
      throw e;
    } catch (RestClientException e) {
      throw unavailable("jwks", e);
    }
    if (set == null || !(set.get("keys") instanceof List<?>)) {
      throw unavailable("jwks", null);
    }
    return set;
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

  /** A 401 means the cached token is stale (expired, realm reset): fetch a new one, once. */
  private <T> T withToken(Supplier<T> call) {
    try {
      return call.get();
    } catch (HttpClientErrorException.Unauthorized e) {
      tokens.invalidate();
      return call.get();
    }
  }

  private static MwException refused(String op, HttpClientErrorException e) {
    log.atWarn()
        .addKeyValue("event", "crypto.call.refused")
        .addKeyValue("op", op)
        .addKeyValue("status", e.getStatusCode().value())
        .log("mw-crypto refused the call");
    return new MwException(ErrorCodes.SYS_CRYPTO_REFUSED, "mw-crypto refused " + op, e).withRetryable(false);
  }

  private <T> T get(String path, Class<T> type) {
    RestClient.RequestHeadersSpec<?> req =
        rest.get().uri(path).accept(MediaType.APPLICATION_JSON).header("Authorization", "Bearer " + tokens.token());
    String correlationId = MDC.get("correlationId");
    if (correlationId != null) {
      req = req.header("X-Correlation-Id", correlationId);
    }
    return req.retrieve().body(type);
  }

  private <T> T post(String path, Object body, Class<T> type) {
    RestClient.RequestBodySpec req =
        rest.post()
            .uri(path)
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .header("Authorization", "Bearer " + tokens.token());
    String correlationId = MDC.get("correlationId");
    if (correlationId != null) {
      req = req.header("X-Correlation-Id", correlationId);
    }
    return req.body(body).retrieve().body(type);
  }

  private static MwException unavailable(String op, Throwable cause) {
    log.atWarn()
        .addKeyValue("event", "crypto.call.failed")
        .addKeyValue("op", op)
        .addKeyValue("errorClass", cause == null ? "BadAnswer" : cause.getClass().getSimpleName())
        .log("mw-crypto unavailable; failing closed");
    return new MwException(ErrorCodes.SYS_CRYPTO_UNAVAILABLE, "mw-crypto unavailable: " + op, cause)
        .withStatus(HttpStatus.SERVICE_UNAVAILABLE);
  }
}
