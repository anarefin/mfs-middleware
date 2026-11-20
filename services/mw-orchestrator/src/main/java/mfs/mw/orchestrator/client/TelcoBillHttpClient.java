package mfs.mw.orchestrator.client;

import java.net.http.HttpClient;
import mfs.mw.orchestrator.config.TopupProperties;
import mfs.mw.orchestrator.exception.MwException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * mw-adapter-telco's bill fetch ({@code POST /telco/v1/bills/fetch}, contract {@code
 * api/mw-adapter-telco/telco.v1.openapi.yaml}). One read on the preview's thread. A partner
 * refusal passes through; no answer is {@code MW-SYS-PARTNER_UNAVAILABLE}.
 */
@Component
public class TelcoBillHttpClient implements TelcoBillClient {

  static final String PATH = "/telco/v1/bills/fetch";
  static final String UNAVAILABLE = "MW-SYS-PARTNER_UNAVAILABLE";

  private static final Logger log = LoggerFactory.getLogger(TelcoBillHttpClient.class);

  private final RestClient http;
  private final JsonMapper json;

  public TelcoBillHttpClient(TopupProperties props, JsonMapper json) {
    TopupProperties.Bill bill = props.bill();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(bill.connectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build());
    factory.setReadTimeout(bill.readTimeout());
    this.http = RestClient.builder().baseUrl(bill.baseUrl().toString()).requestFactory(factory).build();
    this.json = json;
  }

  @Override
  public Bill fetch(String partnerId, String msisdn, String correlationId) {
    byte[] body = json.writeValueAsBytes(json.createObjectNode().put("partnerId", partnerId).put("msisdn", msisdn));
    Answer answer;
    try {
      RestClient.RequestBodySpec req = http.post().uri(PATH)
          .accept(MediaType.APPLICATION_JSON)
          .contentType(MediaType.APPLICATION_JSON);
      if (correlationId != null) {
        req = req.header("X-Correlation-Id", correlationId);
      }
      answer = req.body(body).exchange((request, response) ->
          new Answer(response.getStatusCode().value(), response.getBody().readAllBytes()));
    } catch (RestClientException e) {
      throw unavailable(partnerId, e);
    }
    if (answer.status() == 200) {
      return bill(partnerId, answer.body());
    }
    MwException passed = envelope(answer.body());
    if (passed != null) {
      throw passed;
    }
    throw unavailable(partnerId, null);
  }

  private Bill bill(String partnerId, byte[] body) {
    try {
      JsonNode root = json.readTree(body);
      long outstanding = minor(root.path("outstanding"));
      long minimum = minor(root.path("minimum"));
      if (minimum < 0 || outstanding < minimum) {
        throw unavailable(partnerId, null);
      }
      return new Bill(outstanding, minimum);
    } catch (JacksonException | IllegalArgumentException e) {
      throw unavailable(partnerId, e);
    }
  }

  private static long minor(JsonNode amount) {
    JsonNode value = amount.path("valueMinor");
    if (!value.isIntegralNumber() || value.asLong() < 0 || !"BDT".equals(amount.path("currency").asString())) {
      throw new IllegalArgumentException("bill amount");
    }
    return value.asLong();
  }

  private MwException envelope(byte[] body) {
    if (body == null || body.length == 0) {
      return null;
    }
    try {
      JsonNode root = json.readTree(body);
      String code = root.path("code").asString(null);
      if (code == null || !code.startsWith("MW-")) {
        return null;
      }
      MwException error = new MwException(code, root.path("message").asString("The bill could not be fetched"));
      if (root.has("retryable")) {
        error = error.withRetryable(root.path("retryable").asBoolean());
      }
      String bn = root.path("messageBn").asString(null);
      return bn == null ? error : error.withMessageBn(bn);
    } catch (JacksonException e) {
      return null;
    }
  }

  private static MwException unavailable(String partnerId, Throwable cause) {
    log.atWarn()
        .addKeyValue("event", "txn.topup.bill_unavailable")
        .addKeyValue("partnerId", partnerId)
        .setCause(cause)
        .log("postpaid bill fetch gave no usable answer");
    return new MwException(UNAVAILABLE, "The operator could not be asked for the bill; try again", cause)
        .withStatus(HttpStatus.SERVICE_UNAVAILABLE)
        .withRetryable(true);
  }

  private record Answer(int status, byte[] body) {}
}
