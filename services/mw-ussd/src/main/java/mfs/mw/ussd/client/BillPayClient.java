package mfs.mw.ussd.client;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import mfs.mw.ussd.config.BillPayProperties;
import mfs.mw.ussd.dto.Bill;
import mfs.mw.ussd.dto.Biller;
import mfs.mw.ussd.dto.ErrorEnvelope;
import mfs.mw.ussd.exception.BillPayRefusedException;
import mfs.mw.ussd.exception.BillPayUnavailableException;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Internal HTTP client of mw-adapter-billpay ({@code contracts/api/mw-adapter-billpay/
 * billpay.v1.openapi.yaml}): biller catalogue and bill fetch for the USSD menu (DYNAMIC billers,
 * bill lookup; read-only, the payment goes through Kafka). Copied from mw-app-gateway. No retry:
 * the MNO holds the USSD step open, so a call gets one short attempt; Resilience4j instance
 * {@code billpay} breaks the circuit on transport failures, while the adapter's error envelope is
 * a definite answer and not counted. Runs on the MNO request's (virtual) thread, never on a Kafka
 * thread.
 */
@Component
public class BillPayClient {

  private static final TypeReference<List<Biller>> BILLERS = new TypeReference<>() {};

  private final BillPayProperties props;
  private final JsonMapper json;
  private final HttpClient http;

  public BillPayClient(BillPayProperties props, JsonMapper json) {
    this.props = props;
    this.json = json;
    this.http =
        HttpClient.newBuilder()
            .connectTimeout(props.connectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  @CircuitBreaker(name = "billpay")
  public List<Biller> billers() {
    return json.readValue(send(request("/billpay/v1/billers").GET().build()), BILLERS);
  }

  @CircuitBreaker(name = "billpay")
  public Bill fetch(String billerCode, String billNumber) {
    String path =
        "/billpay/v1/billers/"
            + URLEncoder.encode(billerCode, StandardCharsets.UTF_8).replace("+", "%20")
            + "/bills/fetch";
    byte[] body = json.writeValueAsBytes(Map.of("billNumber", billNumber));
    HttpRequest req =
        request(path)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build();
    return json.readValue(send(req), Bill.class);
  }

  private HttpRequest.Builder request(String path) {
    String base = props.baseUrl().toString().replaceAll("/+$", "");
    HttpRequest.Builder b =
        HttpRequest.newBuilder(URI.create(base + path))
            .timeout(props.readTimeout())
            .header("Accept", "application/json");
    String correlationId = MDC.get("correlationId");
    if (correlationId != null) {
      b.header("X-Correlation-Id", correlationId);
    }
    return b;
  }

  /** The 2xx body, or a refusal (envelope) / unavailability; never a raw transport exception. */
  private byte[] send(HttpRequest request) {
    HttpResponse<byte[]> res;
    try {
      res = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
    } catch (IOException e) {
      throw new BillPayUnavailableException("billpay adapter not reachable", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new BillPayUnavailableException("interrupted", e);
    }
    byte[] body = res.body() == null ? new byte[0] : res.body();
    if (res.statusCode() >= 200 && res.statusCode() < 300) {
      try {
        json.readTree(body);
      } catch (JacksonException e) {
        throw new BillPayUnavailableException("billpay adapter answer unreadable", e);
      }
      return body;
    }
    ErrorEnvelope envelope = envelope(body);
    if (envelope == null) {
      throw new BillPayUnavailableException("billpay adapter answered " + res.statusCode(), null);
    }
    throw new BillPayRefusedException(res.statusCode(), envelope);
  }

  private ErrorEnvelope envelope(byte[] body) {
    try {
      ErrorEnvelope e = json.readValue(body, ErrorEnvelope.class);
      return e == null || e.code() == null || e.code().isBlank() ? null : e;
    } catch (JacksonException e) {
      return null;
    }
  }
}
