package mfs.mw.simulators;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@code samplebiller} profile (ticket 136, FRS CON-06): the other side of the sample connector
 * {@code deploy/partner-routes/examples/sample-rest-biller.yaml}, a REST JSON biller with an API key in
 * {@code X-Api-Key}, a mock party on the mock-control engine with seeded, stateful bills.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "sim.timeout-delay=300ms")
@Import(TestInfrastructure.class)
class SampleBillerMockIT {

  static final Map<String, String> KEY = Map.of("X-Api-Key", "fake-sample-key");

  private final HttpClient http = HttpClient.newHttpClient();
  private final JsonMapper json = JsonMapper.builder().build();

  @LocalServerPort int port;

  @AfterEach
  void reset() throws Exception {
    send("DELETE", "/mock-control/samplebiller/journal", null, Map.of());
    send("DELETE", "/mock-control/samplebiller/scenarios", null, Map.of());
  }

  @Test
  void samplebillerIsAMockParty() throws Exception {
    JsonNode parties = json.readTree(send("GET", "/mock-control/parties", null, Map.of()).body());
    assertThat(parties.findValuesAsString("name")).contains("samplebiller");
  }

  @Test
  void aSeededBillIsFetchedPaidOnceAndReversed() throws Exception {
    String bill = "{\"billerCode\":\"SAMPLEWATER\",\"billNumber\":\"1360000001\"}";
    assertThat(post("/samplebiller/v1/bills/inquiry", bill, Map.of()).statusCode()).as("no key").isEqualTo(401);
    JsonNode due = json.readTree(post("/samplebiller/v1/bills/inquiry", bill, KEY).body());
    assertThat(due.get("status").asString()).isEqualTo("DUE");
    assertThat(due.get("amount").asString()).isEqualTo("845.00");
    assertThat(due.get("billPeriod").asString()).isEqualTo("2026-11");

    String ref = "txn-" + UUID.randomUUID();
    HttpResponse<String> paid = pay(ref, "1360000001", "845.00");
    assertThat(paid.statusCode()).as(paid.body()).isEqualTo(200);
    JsonNode payment = json.readTree(paid.body());
    assertThat(payment.get("status").asString()).isEqualTo("PAID");
    assertThat(payment.get("receipt").asString()).startsWith("SAMPLEBILLER-");

    HttpResponse<String> again = pay("txn-" + UUID.randomUUID(), "1360000001", "845.00");
    assertThat(again.statusCode()).isEqualTo(422);
    assertThat(json.readTree(again.body()).get("code").asString()).isEqualTo("SB02");

    assertThat(json.readTree(send("GET", "/samplebiller/v1/payments/" + ref, null, KEY).body()).get("receipt").asString())
        .isEqualTo(payment.get("receipt").asString());
    HttpResponse<String> rev = post("/samplebiller/v1/payments/" + ref + "/reverse", "{}", KEY);
    assertThat(rev.statusCode()).as(rev.body()).isEqualTo(200);
    assertThat(json.readTree(rev.body()).get("status").asString()).isEqualTo("REVERSED");
  }

  @Test
  void theHealthProbeIsUpUnlessTheSwitchSaysMaintenance() throws Exception {
    assertThat(send("GET", "/samplebiller/v1/health", null, KEY).statusCode()).isEqualTo(200);
    assertThat(send("PUT", "/mock-control/samplebiller/switch", "{\"state\":\"MAINTENANCE\"}", Map.of()).statusCode())
        .isEqualTo(200);
    assertThat(send("GET", "/samplebiller/v1/health", null, KEY).statusCode()).isEqualTo(503);
  }

  @Test
  void theDataTriggersAnswerInTheSampleCodes() throws Exception {
    assertThat(code(post("/samplebiller/v1/bills/inquiry", "{\"billerCode\":\"SAMPLEWATER\",\"billNumber\":\"404\"}", KEY)))
        .isEqualTo("SB01");
    assertThat(code(post("/samplebiller/v1/bills/inquiry", "{\"billerCode\":\"NOPE\",\"billNumber\":\"1\"}", KEY)))
        .isEqualTo("SB04");
    assertThat(code(pay("txn-" + UUID.randomUUID(), "1360000002", "1.00"))).isEqualTo("SB06");
  }

  private String code(HttpResponse<String> res) {
    assertThat(res.statusCode()).as(res.body()).isEqualTo(422);
    return json.readTree(res.body()).get("code").asString();
  }

  private HttpResponse<String> pay(String ref, String billNumber, String amount) throws Exception {
    return post("/samplebiller/v1/payments", "{\"transactionId\":\"" + ref + "\",\"billerCode\":\"SAMPLEWATER\","
        + "\"billNumber\":\"" + billNumber + "\",\"amount\":\"" + amount + "\"}", KEY);
  }

  private HttpResponse<String> post(String path, String body, Map<String, String> headers) throws Exception {
    return send("POST", path, body, headers);
  }

  private HttpResponse<String> send(String method, String path, String body, Map<String, String> headers)
      throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    headers.forEach(b::header);
    if (body == null) {
      b.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
    }
    return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }
}
