package mfs.mw.simulators;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * UCB ATM mock (ticket 120a, FRS EXT-16): dispense status of a cash-out by our stable reference.
 * DISPENSED by default; a business code {@code 5404} is NOT_FOUND and any other is NOT_DISPENSED.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestInfrastructure.class)
class UcbAtmSimulatorIT {

  private final HttpClient http = HttpClient.newHttpClient();
  private final JsonMapper json = JsonMapper.builder().build();

  @LocalServerPort int port;

  @BeforeEach
  void reset() throws Exception {
    send("DELETE", "/mock-control/ucbatm/journal", null);
  }

  @Test
  void aDispenseIsDispensedUntilMockControlSaysOtherwise() throws Exception {
    HttpResponse<String> ok = send("GET", "/ucbatm/v1/dispenses/BITS1", null);
    assertThat(ok.statusCode()).isEqualTo(200);
    JsonNode body = json.readTree(ok.body());
    assertThat(body.path("status").asString()).isEqualTo("DISPENSED");
    assertThat(body.path("dispenseRef").asString()).isEqualTo("dsp-1");

    send("POST", "/mock-control/ucbatm/scenarios",
        "{\"operation\":\"status\",\"behaviour\":\"BUSINESS_ERROR\",\"code\":\"5101\",\"nextN\":1}");
    HttpResponse<String> jammed = send("GET", "/ucbatm/v1/dispenses/BITS1", null);
    assertThat(jammed.statusCode()).isEqualTo(200);
    assertThat(json.readTree(jammed.body()).path("status").asString()).isEqualTo("NOT_DISPENSED");
    assertThat(json.readTree(jammed.body()).path("code").asString()).isEqualTo("5101");

    send("POST", "/mock-control/ucbatm/scenarios",
        "{\"operation\":\"status\",\"behaviour\":\"BUSINESS_ERROR\",\"code\":\"5404\",\"nextN\":1}");
    HttpResponse<String> missing = send("GET", "/ucbatm/v1/dispenses/BITS1", null);
    assertThat(missing.statusCode()).isEqualTo(404);
    assertThat(json.readTree(missing.body()).path("status").asString()).isEqualTo("NOT_FOUND");
  }

  private HttpResponse<String> send(String method, String path, String body) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    if (body == null) {
      b.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
    }
    return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }
}
