package mfs.mw.simulators;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Every OCS scenario reached over real HTTP through magic values and the control API (ticket 15). */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "sim.timeout-delay=300ms")
@Import(TestInfrastructure.class)
class OcsSimulatorIT {

  private static final String MSISDN = "01712345678";

  private final HttpClient http = HttpClient.newHttpClient();
  private final JsonMapper json = JsonMapper.builder().build();
  private HttpServer callbackServer;

  @LocalServerPort int port;

  private HttpResponse<String> send(String method, String path, String body) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    if (body == null) {
      b.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
    }
    return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> topup(String id, String msisdn, String amount) throws Exception {
    return send(
        "POST",
        "/ocs/v1/topup",
        "{\"transactionId\":\"%s\",\"msisdn\":\"%s\",\"amount\":\"%s\"}".formatted(id, msisdn, amount));
  }

  private JsonNode status(String id) throws Exception {
    return json.readTree(send("GET", "/ocs/v1/topup/" + id, null).body());
  }

  @AfterEach
  void reset() throws Exception {
    send("DELETE", "/sim/ocs/scenario", null);
    send("DELETE", "/sim/ocs/requests", null);
    if (callbackServer != null) {
      callbackServer.stop(0);
      callbackServer = null;
    }
  }

  @Test
  void success() throws Exception {
    HttpResponse<String> res = topup("IT-ok", MSISDN, "100.00");
    assertThat(res.statusCode()).isEqualTo(200);
    assertThat(json.readTree(res.body()).get("code").asString()).isEqualTo("0000");
  }

  @Test
  void businessFailureByMsisdnSuffix() throws Exception {
    HttpResponse<String> res = topup("IT-bf", "01712345999", "100.00");
    assertThat(res.statusCode()).isEqualTo(422);
    assertThat(status("IT-bf").get("status").asString()).isEqualTo("FAILED");
  }

  @Test
  void timeoutByAmountAndInquiryShowsTheRealOutcome() throws Exception {
    long t0 = System.nanoTime();
    HttpResponse<String> res = topup("IT-to", MSISDN, "100.13");
    assertThat((System.nanoTime() - t0) / 1_000_000).isGreaterThanOrEqualTo(250);
    assertThat(res.statusCode()).isEqualTo(200);
    assertThat(status("IT-to").get("status").asString()).isEqualTo("SUCCESS");
  }

  @Test
  void serverErrorAfterSend() throws Exception {
    assertThat(topup("IT-5xx", MSISDN, "100.77").statusCode()).isEqualTo(500);
    assertThat(status("IT-5xx").get("status").asString()).isEqualTo("SUCCESS");
  }

  @Test
  void unknownCode() throws Exception {
    HttpResponse<String> res = topup("IT-unk", MSISDN, "100.99");
    assertThat(res.statusCode()).isEqualTo(422);
    assertThat(json.readTree(res.body()).get("code").asString()).isEqualTo("9876");
  }

  @Test
  void malformedReplyIsNotJson() throws Exception {
    HttpResponse<String> res = topup("IT-bad", MSISDN, "100.55");
    assertThat(res.statusCode()).isEqualTo(200);
    org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () -> json.readTree(res.body()));
    assertThat(status("IT-bad").get("status").asString()).isEqualTo("SUCCESS");
  }

  @Test
  void controlApiForcesOutagesAndShowsTheScenario() throws Exception {
    assertThat(send("POST", "/sim/ocs/scenario", "{\"outage\":true}").statusCode()).isEqualTo(200);
    assertThat(json.readTree(send("GET", "/sim/ocs/scenario", null).body()).get("outage").asBoolean()).isTrue();
    assertThat(topup("IT-out", MSISDN, "100.00").statusCode()).isEqualTo(503);
    send("DELETE", "/sim/ocs/scenario", null);
    assertThat(topup("IT-out", MSISDN, "100.00").statusCode()).isEqualTo(200);
  }

  @Test
  void controlApiForcesAMode() throws Exception {
    send("POST", "/sim/ocs/scenario", "{\"forceMode\":\"BUSINESS_FAILURE\"}");
    assertThat(topup("IT-force", MSISDN, "100.00").statusCode()).isEqualTo(422);
  }

  @Test
  void invalidScenarioAndUnknownPartnerAreRejectedWithTheEnvelope() throws Exception {
    assertThat(send("POST", "/sim/ocs/scenario", "{\"errorRate\":2}").statusCode()).isEqualTo(400);
    HttpResponse<String> res = send("GET", "/sim/nope/scenario", null);
    assertThat(res.statusCode()).isEqualTo(404);
    assertThat(json.readTree(res.body()).get("code").asString()).isEqualTo("MW-VAL-003");
  }

  @Test
  void invalidTopupBodyIsA400() throws Exception {
    assertThat(topup("IT-inv", "123", "abc").statusCode()).isEqualTo(400);
  }

  @Test
  void statusOfAnUnknownTransactionIs404() throws Exception {
    assertThat(send("GET", "/ocs/v1/topup/IT-never", null).statusCode()).isEqualTo(404);
  }

  @Test
  void requestsAreRecordedQueriedAndReset() throws Exception {
    topup("IT-rec", MSISDN, "100.00");
    status("IT-rec");
    JsonNode all = json.readTree(send("GET", "/sim/ocs/requests", null).body());
    assertThat(all).hasSize(2);
    assertThat(all.get(0).get("method").asString()).isEqualTo("POST");
    assertThat(all.get(0).get("body").asString()).contains("IT-rec").contains("100.00");
    JsonNode topups = json.readTree(send("GET", "/sim/ocs/requests?path=/topup/IT-rec", null).body());
    assertThat(topups).hasSize(1);
    assertThat(send("DELETE", "/sim/ocs/requests", null).statusCode()).isEqualTo(204);
    assertThat(json.readTree(send("GET", "/sim/ocs/requests", null).body())).isEmpty();
  }

  @Test
  void rejectedRequestsAreRecordedToo() throws Exception {
    topup("IT-rej", "123", "abc");
    assertThat(json.readTree(send("GET", "/sim/ocs/requests", null).body())).hasSize(1);
  }

  @Test
  void duplicateCallbackAndReplayOnDemand() throws Exception {
    List<String> received = new CopyOnWriteArrayList<>();
    callbackServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    callbackServer.createContext(
        "/cb",
        ex -> {
          received.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          ex.sendResponseHeaders(200, -1);
          ex.close();
        });
    callbackServer.start();
    String url = "http://127.0.0.1:" + callbackServer.getAddress().getPort() + "/cb";
    send("POST", "/sim/ocs/scenario", "{\"callbackMode\":\"DUPLICATE\",\"callbackUrl\":\"%s\"}".formatted(url));
    topup("IT-cb", MSISDN, "100.00");
    long deadline = System.currentTimeMillis() + 5000;
    while (received.size() < 2 && System.currentTimeMillis() < deadline) {
      Thread.sleep(20);
    }
    assertThat(received).hasSize(2);

    HttpResponse<String> replay = send("POST", "/sim/ocs/callbacks/IT-cb/replay", null);
    assertThat(replay.statusCode()).isEqualTo(200);
    assertThat(json.readTree(replay.body()).get("targetStatus").asInt()).isEqualTo(200);
    assertThat(received).hasSize(3);
    assertThat(send("POST", "/sim/ocs/callbacks/IT-missing/replay", null).statusCode()).isEqualTo(404);
  }
}
