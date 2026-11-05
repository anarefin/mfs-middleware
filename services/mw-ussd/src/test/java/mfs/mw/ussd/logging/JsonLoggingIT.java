package mfs.mw.ussd.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import mfs.mw.ussd.TestInfrastructure;
import mfs.mw.ussd.util.Sensitive;
import mfs.mw.ussd.util.SensitiveType;
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
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * A log line written while serving a request is one JSON object with the 21.1 fields, carries the
 * request's correlationId and shows sensitive values masked, never raw (18.3).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import({JsonLoggingIT.LoggingController.class, TestInfrastructure.class})
@ExtendWith(OutputCaptureExtension.class)
class JsonLoggingIT {

  static final String RAW_MSISDN = "01712345789";

  record Payer(
      @Sensitive(SensitiveType.MSISDN) String msisdn,
      @Sensitive(SensitiveType.NAME) String name) {}

  @RestController
  static class LoggingController {
    private static final Logger log = LoggerFactory.getLogger(LoggingController.class);

    @GetMapping("/test/log")
    String logSomething() {
      log.atInfo()
          .addKeyValue("event", "txn.state.changed")
          .addKeyValue("txnId", "0190a7c4-1b2e-7c3d-8e4f-5a6b7c8d9e02")
          .log("payer {} paid, callback to " + RAW_MSISDN, new Payer(RAW_MSISDN, "Rahim Uddin"));
      return "ok";
    }
  }

  @LocalServerPort int port;

  @Test
  void requestLogLineIsMaskedJsonWithCorrelationId(CapturedOutput output) throws Exception {
    HttpResponse<String> res =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/test/log"))
                    .header("X-Correlation-Id", "corr-log-1")
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    assertThat(res.statusCode()).isEqualTo(200);

    assertThat(output.getOut()).doesNotContain(RAW_MSISDN).doesNotContain("Rahim");

    String line =
        output.getOut().lines().filter(l -> l.contains("txn.state.changed")).findFirst().orElseThrow();
    @SuppressWarnings("unchecked")
    Map<String, Object> json = JsonMapper.builder().build().readValue(line, Map.class);

    assertThat(json)
        .containsEntry("correlationId", "corr-log-1")
        .containsEntry("event", "txn.state.changed")
        .containsEntry("txnId", "0190a7c4-1b2e-7c3d-8e4f-5a6b7c8d9e02")
        .containsEntry("level", "INFO")
        .containsEntry("service", "mw-ussd")
        .containsKeys("ts", "version", "host", "env", "traceId", "spanId");
    assertThat((String) json.get("ts")).matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d(\\.\\d+)?Z");
    assertThat((String) json.get("message"))
        .isEqualTo("payer Payer[msisdn=0171****789, name=R.] paid, callback to 0171****789");
  }

  @Test
  void everyLineIsJson(CapturedOutput output) {
    LoggerFactory.getLogger(JsonLoggingIT.class).info("plain line for {}", List.of(RAW_MSISDN));
    String line =
        output.getOut().lines().filter(l -> l.contains("plain line")).findFirst().orElseThrow();
    assertThat(line).startsWith("{").endsWith("}").doesNotContain(RAW_MSISDN);
  }
}
