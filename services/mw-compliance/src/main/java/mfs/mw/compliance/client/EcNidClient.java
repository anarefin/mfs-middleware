package mfs.mw.compliance.client;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import mfs.mw.compliance.config.ComplianceProperties;
import mfs.mw.compliance.dto.EcPerson;
import mfs.mw.compliance.dto.EcVerifyRequest;
import mfs.mw.compliance.exception.ErrorCodes;
import mfs.mw.compliance.exception.MwException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * The Election Commission NID service (FRS EXT-07; ticket 124; no Camel, ADR D32): {@code POST
 * /ec/v1/nid/verify} with HTTP Basic credentials from Vault ({@code mw.compliance.ec.*}), so the EC mock and the
 * real service differ by configuration only. 200 is the citizen's record, 404 an unknown NID.
 *
 * <p>Resilience4j: the read timeout is {@code mw.compliance.ec.timeout}; the call is idempotent (same request id),
 * so {@code ec-nid} retries it on a connection failure or a 5xx, never after a timeout (the EC may still be
 * working); circuit breaker {@code ec-nid}. A timeout is {@code MW-TMO-EC_NID} (504), an open breaker {@code
 * MW-SYS-PARTNER_CIRCUIT_OPEN} (503), anything else {@code MW-SYS-002} (503); all retryable. Nothing sent or
 * received is logged.
 */
@Component
public class EcNidClient {

  static final String NAME = "ec-nid";
  static final String PATH = "/ec/v1/nid/verify";
  private static final Logger log = LoggerFactory.getLogger(EcNidClient.class);

  private final RestClient rest;
  private final CircuitBreaker breaker;
  private final Retry retry;
  private final String basic;

  public EcNidClient(ComplianceProperties props, CircuitBreakerRegistry breakers, RetryRegistry retries) {
    ComplianceProperties.Ec ec = props.ec();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1).connectTimeout(ec.connectTimeout())
        .followRedirects(HttpClient.Redirect.NEVER).build());
    factory.setReadTimeout(ec.timeout());
    this.rest = RestClient.builder().baseUrl(ec.baseUrl().toString()).requestFactory(factory).build();
    this.breaker = breakers.circuitBreaker(NAME);
    // The configured instance's waits and attempts, narrowed to what is safe to repeat. Retry.of, not the
    // registry: the registry hands back the instance it already built from application.yml, predicate and all.
    RetryConfig base = retries.retry(NAME).getRetryConfig();
    this.retry = Retry.of(NAME, RetryConfig.from(base).retryOnException(EcNidClient::retryable).build());
    String user = ec.username() == null ? "" : ec.username();
    String pass = ec.password() == null ? "" : ec.password();
    this.basic = "Basic " + Base64.getEncoder().encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
  }

  /** The EC's record of the NID, empty when the EC does not know it. */
  public Optional<EcPerson> verify(EcVerifyRequest request) {
    try {
      return breaker.executeSupplier(Retry.decorateSupplier(retry, () -> post(request)));
    } catch (CallNotPermittedException e) {
      throw failure(ErrorCodes.SYS_PARTNER_CIRCUIT_OPEN, HttpStatus.SERVICE_UNAVAILABLE, "EC NID circuit open", e);
    } catch (RestClientException e) {
      if (timedOut(e)) {
        throw failure(ErrorCodes.TMO_EC, HttpStatus.GATEWAY_TIMEOUT, "EC NID service timed out", e);
      }
      throw failure(ErrorCodes.SYS_UPSTREAM, HttpStatus.SERVICE_UNAVAILABLE, "EC NID service failed", e);
    }
  }

  private Optional<EcPerson> post(EcVerifyRequest request) {
    RestClient.RequestBodySpec req = rest.post().uri(PATH)
        .contentType(MediaType.APPLICATION_JSON)
        .accept(MediaType.APPLICATION_JSON)
        .header("Authorization", basic)
        .header("X-Request-Id", request.requestId());
    String correlationId = MDC.get("correlationId");
    if (correlationId != null) {
      req = req.header("X-Correlation-Id", correlationId);
    }
    try {
      return Optional.ofNullable(req.body(request).retrieve().body(EcPerson.class));
    } catch (HttpClientErrorException.NotFound e) {
      return Optional.empty();
    }
  }

  static boolean retryable(Throwable t) {
    if (t instanceof HttpServerErrorException) {
      return true;
    }
    return t instanceof ResourceAccessException && !timedOut(t);
  }

  static boolean timedOut(Throwable t) {
    for (Throwable c = t; c != null; c = c.getCause()) {
      if (c instanceof HttpTimeoutException || c instanceof java.net.SocketTimeoutException) {
        return true;
      }
    }
    return false;
  }

  private static MwException failure(String code, HttpStatus status, String message, Throwable cause) {
    log.atWarn().addKeyValue("event", "partner.call.completed").addKeyValue("partnerId", "ec")
        .addKeyValue("errorCode", code)
        .addKeyValue("errorClass", cause.getClass().getSimpleName())
        .log("EC NID verify failed");
    return new MwException(code, message, cause).withStatus(status).withRetryable(true);
  }
}
