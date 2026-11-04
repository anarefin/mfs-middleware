package mfs.mw.checkout.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import mfs.mw.checkout.TestInfrastructure;
import mfs.mw.checkout.exception.MwException;
import mfs.mw.checkout.util.Sensitive;
import mfs.mw.checkout.util.SensitiveType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * Over real HTTP: correlation ID handling (2.2, 21.3), the error envelope (5.3, 18.6), the JSON
 * log fields (21.1) and masking before output (18.3). Expected values are the spec's own.
 */
@Conformance
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import({HttpConformanceIT.ProbeController.class, TestInfrastructure.class})
@ExtendWith(OutputCaptureExtension.class)
class HttpConformanceIT {

  static final String MSISDN = "01712345789";
  static final String NID = "1990123456789";
  static final String ACCOUNT = "0011223344556677";
  static final String NAME = "Rahim Uddin";
  static final String PIN_CIPHERTEXT = "kid-1:QmFzZTY0Q2lwaGVydGV4dA==";

  static final List<String> LOG_FIELDS =
      List.of("ts", "level", "service", "version", "host", "env", "correlationId", "traceId", "spanId", "message");

  record Customer(
      @Sensitive(SensitiveType.MSISDN) String msisdn,
      @Sensitive(SensitiveType.NID) String nid,
      @Sensitive(SensitiveType.ACCOUNT) String account,
      @Sensitive(SensitiveType.NAME) String name,
      @Sensitive(SensitiveType.SECRET) String encryptedPin) {}

  @RestController
  static class ProbeController {
    private static final Logger log = LoggerFactory.getLogger(ProbeController.class);

    @GetMapping("/conformance/customers/{msisdn}")
    String customer(@PathVariable String msisdn) {
      log.atInfo()
          .addKeyValue("event", "conformance.customer.seen")
          .addKeyValue("txnId", "TXN-HTTP-1")
          .addKeyValue("durationMs", 12)
          .log("customer {}", new Customer(msisdn, NID, ACCOUNT, NAME, PIN_CIPHERTEXT));
      return "ok";
    }

    @GetMapping("/conformance/customers/{msisdn}/reject")
    void reject(@PathVariable String msisdn) {
      throw new MwException("MW-CORE-INSUFFICIENT-BAL", "Insufficient balance").withTxnId("TXN-HTTP-2");
    }

    @GetMapping("/conformance/bug")
    void bug() {
      throw new IllegalStateException("internal detail for " + MSISDN);
    }
  }

  @LocalServerPort int port;

  HttpResponse<String> get(String path, String correlationId) throws Exception {
    HttpRequest.Builder req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    if (correlationId != null) {
      req.header("X-Correlation-Id", correlationId);
    }
    return HttpClient.newHttpClient().send(req.build(), HttpResponse.BodyHandlers.ofString());
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> json(String text) {
    return JsonMapper.builder().build().readValue(text, Map.class);
  }

  static Map<String, Object> logLine(CapturedOutput output, String containing) {
    String line =
        output.getOut().lines().filter(l -> l.contains(containing)).findFirst().orElseThrow();
    return json(line);
  }

  @Test
  void correlationIdIsEchoedOrGeneratedAndUnsafeOnesAreReplaced() throws Exception {
    assertThat(get("/conformance/customers/" + MSISDN, "corr-conf-1").headers().firstValue("X-Correlation-Id"))
        .hasValue("corr-conf-1");

    String generated = get("/conformance/customers/" + MSISDN, null).headers().firstValue("X-Correlation-Id").orElseThrow();
    assertThat(generated).matches("[0-9a-f-]{36}");

    String replaced =
        get("/conformance/customers/" + MSISDN, "<script>alert(1)</script>").headers().firstValue("X-Correlation-Id").orElseThrow();
    assertThat(replaced).matches("[0-9a-f-]{36}");
  }

  @Test
  void businessErrorIsTheEnvelopeWithTheRequestsCorrelationId(CapturedOutput output) throws Exception {
    HttpResponse<String> res = get("/conformance/customers/" + MSISDN + "/reject", "corr-conf-2");

    assertThat(res.statusCode()).isEqualTo(422);
    assertThat(json(res.body()))
        .containsOnlyKeys("code", "message", "txnId", "correlationId", "retryable", "details")
        .containsEntry("code", "MW-CORE-INSUFFICIENT-BAL")
        .containsEntry("message", "Insufficient balance")
        .containsEntry("txnId", "TXN-HTTP-2")
        .containsEntry("correlationId", "corr-conf-2")
        .containsEntry("retryable", false)
        .containsEntry("details", List.of());

    Map<String, Object> line = logLine(output, "http.request.rejected");
    assertThat(line).containsEntry("correlationId", "corr-conf-2").containsEntry("errorCode", "MW-CORE-INSUFFICIENT-BAL");
    assertThat(output.getOut()).doesNotContain(MSISDN);
  }

  @Test
  void unexpectedErrorLeaksNoInternalsAndCarriesAGeneratedCorrelationId(CapturedOutput output) throws Exception {
    HttpResponse<String> res = get("/conformance/bug", null);

    assertThat(res.statusCode()).isEqualTo(500);
    Map<String, Object> body = json(res.body());
    assertThat(body)
        .containsEntry("code", "MW-SYS-001")
        .containsEntry("message", "Internal error")
        .containsEntry("retryable", true)
        .containsEntry("correlationId", res.headers().firstValue("X-Correlation-Id").orElseThrow());
    assertThat(res.body()).doesNotContain("internal detail").doesNotContain("Exception").doesNotContain("at mfs.");
    assertThat(output.getOut()).doesNotContain(MSISDN);
  }

  @Test
  void logLinesAreJsonWithTheSpecFieldsAndSensitiveValuesMasked(CapturedOutput output) throws Exception {
    assertThat(get("/conformance/customers/" + MSISDN, "corr-conf-3").statusCode()).isEqualTo(200);

    Map<String, Object> line = logLine(output, "conformance.customer.seen");
    assertThat(line).containsKeys(LOG_FIELDS.toArray(String[]::new));
    assertThat(line)
        .containsEntry("level", "INFO")
        .containsEntry("service", "mw-checkout")
        .containsEntry("correlationId", "corr-conf-3")
        .containsEntry("txnId", "TXN-HTTP-1")
        .containsEntry("event", "conformance.customer.seen")
        .containsEntry("durationMs", 12);
    assertThat((String) line.get("ts")).matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d(\\.\\d+)?Z");
    assertThat(line.get("message"))
        .isEqualTo(
            "customer Customer[msisdn=0171****789, nid=****789, account=****6677, name=R., encryptedPin=****]");

    assertThat(output.getOut())
        .doesNotContain(MSISDN)
        .doesNotContain(NID)
        .doesNotContain(ACCOUNT)
        .doesNotContain("Rahim")
        .doesNotContain(PIN_CIPHERTEXT);
  }
}
