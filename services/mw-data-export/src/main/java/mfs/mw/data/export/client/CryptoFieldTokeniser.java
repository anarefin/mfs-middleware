package mfs.mw.data.export.client;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import mfs.mw.data.export.config.ExportProperties;
import mfs.mw.data.export.config.ServiceCertificateManager;
import mfs.mw.data.export.exception.ErrorCodes;
import mfs.mw.data.export.exception.MwException;
import mfs.mw.data.export.service.FieldTokeniser;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tokenises one value through mw-crypto {@code POST /internal/v1/tokens} (ticket 128). mTLS when
 * this service has a certificate. The clear value is the request body and is never logged.
 */
@Component
@ConditionalOnProperty(name = "mw.export.crypto.enabled", havingValue = "true")
public class CryptoFieldTokeniser implements FieldTokeniser {

  private final ExportProperties.Crypto props;
  private final ServiceTokenClient tokens;
  private final JsonMapper json;
  private final HttpClient http;
  private final RetryRegistry retries;
  private final CircuitBreakerRegistry breakers;

  public CryptoFieldTokeniser(ExportProperties properties, ServiceTokenClient tokens, JsonMapper json,
      ObjectProvider<ServiceCertificateManager> certificates, RetryRegistry retries, CircuitBreakerRegistry breakers) {
    this.props = properties.crypto();
    this.tokens = tokens;
    this.json = json;
    this.retries = retries;
    this.breakers = breakers;
    HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(props.connectTimeout());
    ServiceCertificateManager certs = certificates.getIfAvailable();
    if (certs != null) {
      builder.sslContext(certs.clientSslContext());
    }
    this.http = builder.build();
  }

  @Override
  public String tokenise(String domain, String value) {
    return io.github.resilience4j.retry.Retry.decorateSupplier(retries.retry("dw-crypto"),
        io.github.resilience4j.circuitbreaker.CircuitBreaker.decorateSupplier(breakers.circuitBreaker("dw-crypto"),
            () -> once(domain, value))).get();
  }

  private String once(String domain, String value) {
    String correlation = MDC.get("correlationId");
    HttpRequest req = HttpRequest.newBuilder(URI.create(props.baseUrl().replaceAll("/+$", "") + "/internal/v1/tokens"))
        .timeout(props.readTimeout())
        .header("Content-Type", "application/json")
        .header("Accept", "application/json")
        .header("Authorization", "Bearer " + tokens.token())
        .header("X-Correlation-Id", correlation == null ? UUID.randomUUID().toString() : correlation)
        .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(Map.of("domain", domain, "values", List.of(value)))))
        .build();
    HttpResponse<byte[]> res;
    try {
      res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
    } catch (IOException e) {
      throw new MwException(ErrorCodes.SYS_UPSTREAM, "mw-crypto tokenise not reachable", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new MwException(ErrorCodes.SYS_UPSTREAM, "mw-crypto tokenise interrupted", e);
    }
    if (res.statusCode() == 401) {
      tokens.invalidate();
    }
    if (res.statusCode() != 200) {
      throw new MwException(ErrorCodes.SYS_UPSTREAM, "mw-crypto tokenise answered " + res.statusCode());
    }
    JsonNode list = json.readTree(res.body()).path("tokens");
    if (!list.isArray() || list.size() != 1) {
      throw new MwException(ErrorCodes.SYS_UPSTREAM, "mw-crypto tokenise answer does not match");
    }
    return list.get(0).asString();
  }
}
