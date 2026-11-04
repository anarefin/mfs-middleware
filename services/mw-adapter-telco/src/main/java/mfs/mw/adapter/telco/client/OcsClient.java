package mfs.mw.adapter.telco.client;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeoutException;
import mfs.mw.adapter.telco.config.OcsProperties;
import mfs.mw.adapter.telco.dto.OcsResponse;
import mfs.mw.adapter.telco.dto.OcsResult;
import mfs.mw.adapter.telco.dto.OcsTopupRequest;
import mfs.mw.adapter.telco.exception.OcsNotSentException;
import mfs.mw.adapter.telco.exception.OcsUnknownOutcomeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Calls the OCS REST/JSON top-up API (wire format: mw-simulators OCS simulator) behind
 * Resilience4j: bulkhead, then circuit breaker, then time limiter (spec 19.2; instance {@code
 * ocs}, 20 s limit). The top-up is not idempotent from the client's side, so there is no retry
 * here: duplicates are absorbed by the stable partner reference, which the OCS deduplicates on.
 *
 * <p>Outcome: a definite {@link OcsResult}; {@link OcsNotSentException} when the request
 * certainly never left (connect failure, open circuit, full bulkhead); {@link
 * OcsUnknownOutcomeException} when it may have been applied (timeout, reset after send, 5xx,
 * unreadable reply). Never log bodies: they carry the MSISDN.
 */
@Component
public class OcsClient {

  static final String INSTANCE = "ocs";
  private static final Logger log = LoggerFactory.getLogger(OcsClient.class);

  private final OcsProperties props;
  private final JsonMapper json;
  private final RestClient rest;
  private final Bulkhead bulkhead;
  private final CircuitBreaker breaker;
  private final TimeLimiter limiter;
  private final ExecutorService executor;

  public OcsClient(
      OcsProperties props,
      JsonMapper json,
      CircuitBreakerRegistry breakers,
      BulkheadRegistry bulkheads,
      TimeLimiterRegistry limiters,
      @Qualifier("telcoCallExecutor") ExecutorService executor) {
    this.props = props;
    this.json = json;
    this.executor = executor;
    this.breaker = breakers.circuitBreaker(INSTANCE);
    this.bulkhead = bulkheads.bulkhead(INSTANCE);
    this.limiter = limiters.timeLimiter(INSTANCE);
    JdkClientHttpRequestFactory factory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(props.connectTimeout()).build());
    factory.setReadTimeout(props.readTimeout());
    this.rest = RestClient.builder().baseUrl(props.baseUrl().toString()).requestFactory(factory).build();
  }

  /** Sends one top-up, once. */
  public OcsResult topup(OcsTopupRequest request) {
    try {
      return bulkhead.executeSupplier(() -> breaker.executeSupplier(() -> timeLimited(request)));
    } catch (BulkheadFullException e) {
      throw new OcsNotSentException(OcsNotSentException.Reason.BULKHEAD_FULL, e);
    } catch (CallNotPermittedException e) {
      throw new OcsNotSentException(OcsNotSentException.Reason.CIRCUIT_OPEN, e);
    }
  }

  private OcsResult timeLimited(OcsTopupRequest request) {
    try {
      return limiter.executeFutureSupplier(
          () -> CompletableFuture.supplyAsync(() -> send(request), executor));
    } catch (TimeoutException e) {
      throw new OcsUnknownOutcomeException(OcsUnknownOutcomeException.Reason.TIMEOUT, e);
    } catch (RuntimeException e) {
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new OcsUnknownOutcomeException(OcsUnknownOutcomeException.Reason.TIMEOUT, e);
    } catch (Exception e) {
      throw new OcsUnknownOutcomeException(OcsUnknownOutcomeException.Reason.CONNECTION_RESET, e);
    }
  }

  private OcsResult send(OcsTopupRequest request) {
    try {
      return rest.post()
          .uri("/ocs/v1/topup")
          .contentType(MediaType.APPLICATION_JSON)
          .body(json.writeValueAsString(request))
          .exchange(
              (req, res) ->
                  classify(res.getStatusCode().value(), res.getBody().readAllBytes()));
    } catch (ResourceAccessException e) {
      throw transport(e);
    }
  }

  private OcsResult classify(int status, byte[] body) {
    if (status >= 500) {
      throw new OcsUnknownOutcomeException(OcsUnknownOutcomeException.Reason.HTTP_5XX, null);
    }
    OcsResponse reply = parse(body);
    if (status == 200) {
      if (reply == null || !"SUCCESS".equals(reply.status()) || isBlank(reply.ocsRef())) {
        throw malformed(status);
      }
      return new OcsResult.Accepted(reply.ocsRef(), reply.code());
    }
    String code;
    if (status == 422) {
      if (reply == null || isBlank(reply.code())) {
        throw malformed(status);
      }
      code = reply.code();
    } else if (status == 400) {
      code = "HTTP_400";
    } else {
      // 408, 409, 429, other 4xx, 1xx, 3xx: not a definite answer, the top-up may have been
      // applied, so never a rejection (a rejection releases the hold).
      log.atWarn()
          .addKeyValue("event", "partner.call.unexpected_status")
          .addKeyValue("httpStatus", status)
          .log("OCS answered with an unexpected status");
      throw new OcsUnknownOutcomeException(
          OcsUnknownOutcomeException.Reason.UNEXPECTED_STATUS, null);
    }
    String message = reply == null ? null : reply.message();
    return props.ambiguousCodes().contains(code)
        ? new OcsResult.Ambiguous(code, message)
        : new OcsResult.Rejected(code, message);
  }

  private OcsResponse parse(byte[] body) {
    try {
      return json.readValue(new String(body, StandardCharsets.UTF_8), OcsResponse.class);
    } catch (JacksonException e) {
      return null;
    }
  }

  private static OcsUnknownOutcomeException malformed(int status) {
    log.atWarn()
        .addKeyValue("event", "partner.call.malformed")
        .addKeyValue("httpStatus", status)
        .log("OCS reply not understood");
    return new OcsUnknownOutcomeException(OcsUnknownOutcomeException.Reason.MALFORMED_REPLY, null);
  }

  /** Connect failures mean nothing was sent; everything else may have been. */
  private static RuntimeException transport(ResourceAccessException e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof ConnectException || t instanceof HttpConnectTimeoutException) {
        log.atWarn()
            .addKeyValue("event", "partner.call.not_sent")
            .addKeyValue("errorClass", t.getClass().getName())
            .addKeyValue("detail", String.valueOf(t.getMessage()))
            .log("OCS connect failed");
        return new OcsNotSentException(OcsNotSentException.Reason.CONNECT_FAILED, e);
      }
      if (t instanceof HttpTimeoutException) {
        return new OcsUnknownOutcomeException(OcsUnknownOutcomeException.Reason.TIMEOUT, e);
      }
    }
    return new OcsUnknownOutcomeException(OcsUnknownOutcomeException.Reason.CONNECTION_RESET, e);
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }
}
