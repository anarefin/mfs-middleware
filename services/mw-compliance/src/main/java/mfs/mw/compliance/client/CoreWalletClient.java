package mfs.mw.compliance.client;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.net.http.HttpClient;
import java.util.UUID;
import mfs.mw.compliance.config.ComplianceProperties;
import mfs.mw.compliance.dto.CoreKycLevelRequest;
import mfs.mw.compliance.dto.KycLevel;
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
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * The core wallet REST API (ADR D31, contract {@code api/mw-core-mock/core-wallet.v1.openapi.yaml}): {@code PUT
 * /core/v1/accounts/{walletId}/kyc-level} after an eKYC match (ticket 124b). Idempotent on the core (the same level
 * changes nothing), so a resubmit may repeat it. Realm token from {@link ServiceTokenClient} (a 401 drops it and
 * retries once); breaker {@code core-wallet}. Unreachable, timed out, open breaker or 5xx is {@code MW-SYS-002}
 * (503, retryable); a refusal (4xx) {@code MW-CORE-KYC_LEVEL_REFUSED} (422, not retryable).
 */
@Component
public class CoreWalletClient {

  static final String BREAKER = "core-wallet";
  private static final Logger log = LoggerFactory.getLogger(CoreWalletClient.class);

  private final RestClient rest;
  private final ServiceTokenClient tokens;
  private final CircuitBreaker breaker;

  public CoreWalletClient(ComplianceProperties props, ServiceTokenClient tokens, CircuitBreakerRegistry breakers) {
    ComplianceProperties.Core core = props.core();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1).connectTimeout(core.connectTimeout())
        .followRedirects(HttpClient.Redirect.NEVER).build());
    factory.setReadTimeout(core.timeout());
    this.rest = RestClient.builder().baseUrl(core.baseUrl().toString()).requestFactory(factory).build();
    this.tokens = tokens;
    this.breaker = breakers.circuitBreaker(BREAKER);
  }

  /** Sets the wallet's KYC level on the core, naming the application that decided it. */
  public void setKycLevel(String walletId, KycLevel level, UUID applicationId) {
    CoreKycLevelRequest body = new CoreKycLevelRequest(level.name(), applicationId.toString());
    try {
      breaker.executeRunnable(() -> {
        try {
          put(walletId, body);
        } catch (HttpClientErrorException.Unauthorized e) {
          tokens.invalidate();
          put(walletId, body);
        }
      });
    } catch (CallNotPermittedException e) {
      throw unavailable(e);
    } catch (HttpClientErrorException e) {
      log.atWarn().addKeyValue("event", "core.call.refused").addKeyValue("status", e.getStatusCode().value())
          .log("The core refused the KYC level");
      throw new MwException(ErrorCodes.CORE_KYC_LEVEL_REFUSED, "The core refused the KYC level", e)
          .withRetryable(false);
    } catch (RestClientException e) {
      throw unavailable(e);
    }
  }

  private void put(String walletId, CoreKycLevelRequest body) {
    RestClient.RequestBodySpec req = rest.put().uri("/core/v1/accounts/{walletId}/kyc-level", walletId)
        .contentType(MediaType.APPLICATION_JSON)
        .accept(MediaType.APPLICATION_JSON)
        .header("Authorization", "Bearer " + tokens.token());
    String correlationId = MDC.get("correlationId");
    if (correlationId != null) {
      req = req.header("X-Correlation-Id", correlationId);
    }
    req.body(body).retrieve().toBodilessEntity();
  }

  private static MwException unavailable(Throwable cause) {
    log.atWarn().addKeyValue("event", "core.call.failed").addKeyValue("errorClass", cause.getClass().getSimpleName())
        .log("Core wallet unavailable for the KYC level");
    return new MwException(ErrorCodes.SYS_UPSTREAM, "Core wallet unavailable", cause)
        .withStatus(HttpStatus.SERVICE_UNAVAILABLE).withRetryable(true);
  }
}
