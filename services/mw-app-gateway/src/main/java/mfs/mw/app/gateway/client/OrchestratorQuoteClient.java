package mfs.mw.app.gateway.client;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.net.http.HttpClient;
import mfs.mw.app.gateway.config.OrchestratorClientProperties;
import mfs.mw.app.gateway.dto.ErrorEnvelope;
import mfs.mw.app.gateway.dto.Quote;
import mfs.mw.app.gateway.dto.QuoteRequest;
import mfs.mw.app.gateway.exception.MwException;
import mfs.mw.app.gateway.exception.OrchestratorUnavailableException;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * mw-orchestrator's quote ({@code POST /internal/v1/quotes}, contract {@code
 * api/mw-orchestrator/txn-query.v1.openapi.yaml} 1.4.0, ticket 105): the core's charges, the payer's
 * limit check and the payee for the preview, always for the signed-in wallet. A read: Resilience4j
 * instance {@code orchestrator} retries it and breaks the circuit on {@link
 * OrchestratorUnavailableException} only. RestClient with a fixed URI template (no id in the path).
 * A 401 drops the service token and asks once more; a 400 / 422 envelope (a core refusal such as
 * {@code MW-CORE-CREDIT_WALLET_NOT_FOUND}) passes through as an {@link MwException}; anything else
 * (connect, timeout, other status, unreadable answer, no token) is {@link OrchestratorUnavailableException}.
 */
@Component
public class OrchestratorQuoteClient {

  static final String PATH = "/internal/v1/quotes";

  private record Answer(int status, byte[] body) {}

  private final RestClient http;
  private final ServiceTokenClient tokens;
  private final JsonMapper json;

  public OrchestratorQuoteClient(OrchestratorClientProperties props, ServiceTokenClient tokens, JsonMapper json) {
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1) // plain HTTP inside the network: no h2c upgrade attempt
            .connectTimeout(props.connectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build());
    factory.setReadTimeout(props.readTimeout());
    this.http = RestClient.builder().baseUrl(props.baseUrl().toString()).requestFactory(factory).build();
    this.tokens = tokens;
    this.json = json;
  }

  @Retry(name = "orchestrator")
  @CircuitBreaker(name = "orchestrator")
  public Quote quote(QuoteRequest body) {
    Answer a = send(body);
    if (a.status() == 401) {
      tokens.invalidate();
      a = send(body);
    }
    if (a.status() == 200) {
      try {
        return json.readValue(a.body(), Quote.class);
      } catch (JacksonException e) {
        throw new OrchestratorUnavailableException("quote unreadable", e);
      }
    }
    if (a.status() == 400 || a.status() == 422) {
      ErrorEnvelope envelope = envelope(a.body());
      if (envelope != null) {
        MwException refused = new MwException(envelope.code(), envelope.message())
            .withStatus(HttpStatus.valueOf(a.status()))
            .withRetryable(envelope.retryable());
        throw envelope.messageBn() == null ? refused : refused.withMessageBn(envelope.messageBn());
      }
    }
    throw new OrchestratorUnavailableException("orchestrator quote answered " + a.status(), null);
  }

  private Answer send(QuoteRequest body) {
    String token;
    try {
      token = tokens.token();
    } catch (MwException e) {
      throw new OrchestratorUnavailableException("no service token", e);
    }
    try {
      RestClient.RequestBodySpec req = http.post().uri(PATH)
          .accept(MediaType.APPLICATION_JSON)
          .contentType(MediaType.APPLICATION_JSON)
          .header("Authorization", "Bearer " + token);
      String correlationId = MDC.get("correlationId");
      if (correlationId != null) {
        req = req.header("X-Correlation-Id", correlationId);
      }
      return req.body(body).exchange((request, response) ->
          new Answer(response.getStatusCode().value(), response.getBody().readAllBytes()));
    } catch (RestClientException e) {
      throw new OrchestratorUnavailableException("orchestrator not reachable", e);
    }
  }

  private ErrorEnvelope envelope(byte[] body) {
    if (body == null || body.length == 0) {
      return null;
    }
    try {
      ErrorEnvelope e = json.readValue(body, ErrorEnvelope.class);
      return e.code() != null && e.code().startsWith("MW-") ? e : null;
    } catch (JacksonException e) {
      return null;
    }
  }
}
