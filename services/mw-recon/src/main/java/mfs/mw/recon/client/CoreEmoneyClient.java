package mfs.mw.recon.client;

import java.net.http.HttpClient;
import mfs.mw.recon.config.CoreClientProperties;
import mfs.mw.recon.exception.ErrorCodes;
import mfs.mw.recon.exception.MwException;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The e-money total of the core wallet API (ticket 127c, FRS REC-07; contract {@code
 * api/mw-core-mock/core-wallet.v1.openapi.yaml} {@code GET /core/v1/reports/emoney-total}). Read only: mw-recon
 * never calls a core write. Bearer is this service's realm {@code services} token ({@link ServiceTokenClient}),
 * renewed once on a 401 {@code MW-AUTH-001}. Anything but a 200 with a total is {@code MW-SYS-002} (503): no
 * position is computed from a guess. Runs on the scheduler thread; nothing received is logged.
 */
@Component
public class CoreEmoneyClient {

  static final String PATH = "/core/v1/reports/emoney-total";
  static final String TOKEN_REFUSED = "MW-AUTH-001";

  private record Answer(int status, JsonNode body) {}

  private final RestClient http;
  private final ServiceTokenClient tokens;
  private final JsonMapper json;

  public CoreEmoneyClient(CoreClientProperties props, ServiceTokenClient tokens, JsonMapper json) {
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
        .connectTimeout(props.connectTimeout()).followRedirects(HttpClient.Redirect.NEVER).build());
    factory.setReadTimeout(props.timeout());
    this.http = RestClient.builder().baseUrl(props.baseUrl().toString()).requestFactory(factory).build();
    this.tokens = tokens;
    this.json = json;
  }

  /** The sum of every e-money wallet's balance in poisha, as the core reads it now. */
  public long emoneyTotalMinor() {
    Answer a;
    try {
      a = exchange();
      if (a.status() == 401 && a.body() != null && TOKEN_REFUSED.equals(a.body().path("code").asString(null))) {
        tokens.invalidate();
        a = exchange();
      }
    } catch (MwException e) {
      throw e;
    } catch (RuntimeException e) {
      throw unavailable("core not reachable: " + e.getClass().getSimpleName(), e);
    }
    JsonNode total = a.body() == null ? null : a.body().path("total").path("valueMinor");
    if (a.status() != 200 || total == null || !total.isIntegralNumber()) {
      throw unavailable("core answered " + a.status(), null);
    }
    return total.asLong();
  }

  private Answer exchange() {
    RestClient.RequestHeadersSpec<?> req = http.get().uri(PATH)
        .accept(MediaType.APPLICATION_JSON)
        .header("Authorization", "Bearer " + tokens.token());
    String correlationId = MDC.get("correlationId");
    if (correlationId != null) {
      req = req.header("X-Correlation-Id", correlationId);
    }
    return req.exchange((request, res) -> new Answer(res.getStatusCode().value(), read(res.getBody().readAllBytes())));
  }

  private JsonNode read(byte[] bytes) {
    if (bytes == null || bytes.length == 0) {
      return null;
    }
    try {
      return json.readTree(bytes);
    } catch (JacksonException e) {
      return null;
    }
  }

  private static MwException unavailable(String message, Throwable cause) {
    return new MwException(ErrorCodes.SYS_UPSTREAM, "E-money total: " + message, cause)
        .withStatus(HttpStatus.SERVICE_UNAVAILABLE);
  }
}
