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

/** NPSB switch mock (ticket 121a, FRS MCK-01): the seeded participant list, on the mock-control engine. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestInfrastructure.class)
class NpsbSimulatorIT {

  private final HttpClient http = HttpClient.newHttpClient();
  private final JsonMapper json = JsonMapper.builder().build();

  @LocalServerPort int port;

  @BeforeEach
  void reset() throws Exception {
    send("DELETE", "/mock-control/npsb/journal", null);
  }

  @Test
  void theParticipantListIsTheSeedUntilMockControlRefusesIt() throws Exception {
    HttpResponse<String> ok = send("GET", "/npsb/v1/participants", null);
    assertThat(ok.statusCode()).isEqualTo(200);
    JsonNode list = json.readTree(ok.body()).path("participants");
    assertThat(list.size()).isGreaterThanOrEqualTo(10);
    assertThat(list.get(0).path("id").asString()).isEqualTo("FAKEBDD1");
    assertThat(list.get(0).path("kind").asString()).isEqualTo("BANK");

    send("POST", "/mock-control/npsb/scenarios",
        "{\"operation\":\"participants\",\"behaviour\":\"BUSINESS_ERROR\",\"code\":\"NARR\",\"nextN\":1}");
    HttpResponse<String> refused = send("GET", "/npsb/v1/participants", null);
    assertThat(refused.statusCode()).isEqualTo(422);
    assertThat(json.readTree(refused.body()).path("code").asString()).isEqualTo("NARR");

    HttpResponse<String> journal = send("GET", "/mock-control/npsb/journal", null);
    assertThat(journal.body()).contains("/npsb/v1/participants");
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
