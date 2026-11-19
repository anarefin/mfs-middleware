package mfs.mw.simulators;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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

/**
 * The one mock-control API (ticket 102, ADR D33, FRS MCT-01..03, MCT-06) over real HTTP on the OCS:
 * every behaviour of MCT-01, the scopes, the switches (DOWN closes the connection), the journal and its
 * reset, and the old {@code /sim/ocs/scenario} as an alias of the engine.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "sim.timeout-delay=300ms")
@Import(TestInfrastructure.class)
class MockControlControllerIT {

  private static final String MSISDN = "01712345678";

  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private final JsonMapper json = JsonMapper.builder().build();
  private final List<String> callbacks = new CopyOnWriteArrayList<>();
  private HttpServer callbackServer;

  @LocalServerPort int port;

  private HttpResponse<String> send(String method, String path, String body) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(10));
    if (body == null) {
      b.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
    }
    return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> topup(String id, String msisdn, String amount) throws Exception {
    return send("POST", "/ocs/v1/topup",
        "{\"transactionId\":\"%s\",\"msisdn\":\"%s\",\"amount\":\"%s\"}".formatted(id, msisdn, amount));
  }

  private JsonNode scenario(String body) throws Exception {
    HttpResponse<String> res = send("POST", "/mock-control/ocs/scenarios", body);
    assertThat(res.statusCode()).as(res.body()).isEqualTo(201);
    return json.readTree(res.body());
  }

  private String status(String id) throws Exception {
    HttpResponse<String> res = send("GET", "/ocs/v1/topup/" + id, null);
    return res.statusCode() == 404 ? "NONE" : json.readTree(res.body()).get("status").asString();
  }

  private String callbackUrl() throws IOException {
    callbackServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    callbackServer.createContext("/cb", ex -> {
      callbacks.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      ex.sendResponseHeaders(200, -1);
      ex.close();
    });
    callbackServer.start();
    return "http://127.0.0.1:" + callbackServer.getAddress().getPort() + "/cb";
  }

  private void awaitCallbacks(int n, long ms) throws InterruptedException {
    long deadline = System.currentTimeMillis() + ms;
    while (callbacks.size() < n && System.currentTimeMillis() < deadline) {
      Thread.sleep(20);
    }
  }

  @AfterEach
  void reset() throws Exception {
    send("DELETE", "/mock-control/ocs/journal", null);
    if (callbackServer != null) {
      callbackServer.stop(0);
    }
  }

  @Test
  void thePartiesAreListedWithTheirOperationsAndKeys() throws Exception {
    JsonNode parties = json.readTree(send("GET", "/mock-control/parties", null).body());
    assertThat(parties.findValuesAsString("name")).contains("ocs", "billagg", "c2wbank", "merchant");
    assertThat(parties.get(0).get("operations").toString()).contains("topup");
  }

  @Test
  void successBeatsTheMagicMsisdn() throws Exception {
    scenario("{\"operation\":\"topup\",\"behaviour\":\"SUCCESS\"}");
    assertThat(topup("MC-ok", "01712345999", "100.00").statusCode()).isEqualTo(200);
  }

  @Test
  void businessErrorCarriesItsCode() throws Exception {
    scenario("{\"behaviour\":\"BUSINESS_ERROR\",\"code\":\"2042\"}");
    HttpResponse<String> res = topup("MC-be", MSISDN, "100.00");
    assertThat(res.statusCode()).isEqualTo(422);
    assertThat(json.readTree(res.body()).get("code").asString()).isEqualTo("2042");
  }

  @Test
  void timeoutHoldsTheReplyAndDelayDelaysIt() throws Exception {
    scenario("{\"operation\":\"topup\",\"behaviour\":\"TIMEOUT\",\"delayMs\":400,\"nextN\":1}");
    long t0 = System.nanoTime();
    assertThat(topup("MC-to", MSISDN, "100.00").statusCode()).isEqualTo(200);
    assertThat((System.nanoTime() - t0) / 1_000_000).isGreaterThanOrEqualTo(380);
    scenario("{\"operation\":\"topup\",\"behaviour\":\"DELAY\",\"delayMs\":250,\"nextN\":1}");
    t0 = System.nanoTime();
    HttpResponse<String> late = topup("MC-delay", MSISDN, "100.99");
    assertThat((System.nanoTime() - t0) / 1_000_000).isGreaterThanOrEqualTo(230);
    assertThat(late.statusCode()).as("DELAY leaves the magic outcome").isEqualTo(422);
  }

  @Test
  void http5xxAnswersItsStatusAfterApplying() throws Exception {
    scenario("{\"behaviour\":\"HTTP_5XX\",\"httpStatus\":502,\"operation\":\"topup\"}");
    assertThat(topup("MC-5xx", MSISDN, "100.00").statusCode()).isEqualTo(502);
    assertThat(status("MC-5xx")).isEqualTo("SUCCESS");
  }

  @Test
  void malformedResponseIsNotJson() throws Exception {
    scenario("{\"behaviour\":\"MALFORMED_RESPONSE\",\"operation\":\"topup\"}");
    HttpResponse<String> res = topup("MC-bad", MSISDN, "100.00");
    assertThat(res.statusCode()).isEqualTo(200);
    assertThatThrownBy(() -> json.readTree(res.body())).isInstanceOf(Exception.class);
  }

  @Test
  void connectionResetClosesTheSocketAfterApplying() throws Exception {
    scenario("{\"behaviour\":\"CONNECTION_RESET\",\"operation\":\"topup\",\"nextN\":1}");
    assertThatThrownBy(() -> topup("MC-reset", MSISDN, "100.00")).isInstanceOf(IOException.class);
    assertThat(status("MC-reset")).isEqualTo("SUCCESS");
    assertThat(topup("MC-reset2", MSISDN, "100.00").statusCode()).as("nextN used up").isEqualTo(200);
  }

  @Test
  void partialSuccessReturnsThePartysPartialReply() throws Exception {
    scenario("{\"behaviour\":\"PARTIAL_SUCCESS\",\"operation\":\"topup\"}");
    HttpResponse<String> res = topup("MC-part", MSISDN, "100.00");
    assertThat(res.statusCode()).isEqualTo(200);
    assertThat(json.readTree(res.body()).get("status").asString()).isEqualTo("PARTIAL");
    assertThat(json.readTree(res.body()).get("code").asString()).isEqualTo("0002");
  }

  @Test
  void duplicateAndLateCallbacks() throws Exception {
    String url = callbackUrl();
    assertThat(send("PUT", "/mock-control/ocs/settings", "{\"callbackUrl\":\"%s\"}".formatted(url)).statusCode()).isEqualTo(200);
    scenario("{\"behaviour\":\"DUPLICATE_CALLBACK\",\"nextN\":1}");
    topup("MC-dup", MSISDN, "100.00");
    awaitCallbacks(2, 5000);
    assertThat(callbacks).hasSize(2).allMatch(b -> b.contains("MC-dup"));

    callbacks.clear();
    scenario("{\"behaviour\":\"LATE_CALLBACK\",\"delayMs\":600,\"nextN\":1}");
    topup("MC-late", MSISDN, "100.00");
    Thread.sleep(300);
    assertThat(callbacks).as("not yet").isEmpty();
    awaitCallbacks(1, 5000);
    assertThat(callbacks).singleElement().asString().contains("MC-late");
  }

  @Test
  void aKeyScopeOnlyHitsItsMsisdn() throws Exception {
    scenario("{\"behaviour\":\"BUSINESS_ERROR\",\"key\":\"01799999990\"}");
    assertThat(topup("MC-k1", "01799999990", "100.00").statusCode()).isEqualTo(422);
    assertThat(topup("MC-k2", MSISDN, "100.00").statusCode()).isEqualTo(200);
  }

  @Test
  void scenariosAreListedAndDeleted() throws Exception {
    JsonNode s = scenario("{\"behaviour\":\"TIMEOUT\",\"nextN\":3}");
    assertThat(s.get("remaining").asInt()).isEqualTo(3);
    assertThat(json.readTree(send("GET", "/mock-control/ocs/scenarios", null).body())).hasSize(1);
    assertThat(send("DELETE", "/mock-control/ocs/scenarios/" + s.get("id").asString(), null).statusCode()).isEqualTo(204);
    assertThat(send("DELETE", "/mock-control/ocs/scenarios/" + s.get("id").asString(), null).statusCode()).isEqualTo(404);
    scenario("{\"behaviour\":\"TIMEOUT\"}");
    assertThat(send("DELETE", "/mock-control/ocs/scenarios", null).statusCode()).isEqualTo(204);
    assertThat(json.readTree(send("GET", "/mock-control/ocs/scenarios", null).body())).isEmpty();
  }

  @Test
  void badScenariosAreRefused() throws Exception {
    assertThat(send("POST", "/mock-control/ocs/scenarios", "{\"behaviour\":\"TIMEOUT\",\"operation\":\"pay\"}").statusCode())
        .as("not an OCS operation").isEqualTo(400);
    assertThat(send("POST", "/mock-control/ocs/scenarios", "{\"behaviour\":\"NOPE\"}").statusCode()).isEqualTo(400);
    assertThat(send("POST", "/mock-control/ocs/scenarios", "{}").statusCode()).isEqualTo(400);
    HttpResponse<String> unknown = send("POST", "/mock-control/nope/scenarios", "{\"behaviour\":\"TIMEOUT\"}");
    assertThat(unknown.statusCode()).isEqualTo(404);
    assertThat(json.readTree(unknown.body()).get("code").asString()).isEqualTo("MW-VAL-003");
  }

  @Test
  void downRefusesTheConnectionAndAppliesNothing() throws Exception {
    assertThat(send("PUT", "/mock-control/ocs/switch", "{\"state\":\"DOWN\"}").statusCode()).isEqualTo(200);
    assertThatThrownBy(() -> topup("MC-down", MSISDN, "100.00")).isInstanceOf(IOException.class);
    send("PUT", "/mock-control/ocs/switch", "{\"state\":\"UP\"}");
    assertThat(status("MC-down")).isEqualTo("NONE");
  }

  @Test
  void maintenanceAnswersTheMaintenanceReplyAndSlowDelays() throws Exception {
    send("PUT", "/mock-control/ocs/switch", "{\"state\":\"MAINTENANCE\"}");
    HttpResponse<String> res = topup("MC-maint", MSISDN, "100.00");
    assertThat(res.statusCode()).isEqualTo(503);
    assertThat(json.readTree(res.body()).get("code").asString()).isEqualTo("5030");
    JsonNode sw = json.readTree(
        send("PUT", "/mock-control/ocs/switch", "{\"state\":\"SLOW\",\"latencyMinMs\":200,\"latencyMaxMs\":300}").body());
    assertThat(sw.get("latencyMaxMs").asLong()).isEqualTo(300);
    long t0 = System.nanoTime();
    assertThat(topup("MC-slow", MSISDN, "100.00").statusCode()).isEqualTo(200);
    assertThat((System.nanoTime() - t0) / 1_000_000).isGreaterThanOrEqualTo(190);
  }

  @Test
  void theJournalIsQueryableAndItsDeleteResetsThePartysState() throws Exception {
    topup("MC-j1", MSISDN, "100.00");
    topup("MC-j2", MSISDN, "100.00");
    JsonNode all = json.readTree(send("GET", "/mock-control/ocs/journal", null).body());
    assertThat(all).hasSize(2);
    long first = all.get(0).get("seq").asLong();
    assertThat(json.readTree(send("GET", "/mock-control/ocs/journal?contains=MC-j2", null).body())).hasSize(1);
    assertThat(json.readTree(send("GET", "/mock-control/ocs/journal?afterSeq=" + first, null).body())).hasSize(1);
    send("PUT", "/mock-control/ocs/switch", "{\"state\":\"MAINTENANCE\"}");

    assertThat(send("DELETE", "/mock-control/ocs/journal", null).statusCode()).isEqualTo(204);

    assertThat(json.readTree(send("GET", "/mock-control/ocs/journal", null).body())).isEmpty();
    assertThat(json.readTree(send("GET", "/mock-control/ocs/switch", null).body()).get("state").asString()).isEqualTo("UP");
    assertThat(status("MC-j1")).as("the ledger is reset").isEqualTo("NONE");
  }

  @Test
  void theOldScenarioPathIsAnAliasOfTheEngine() throws Exception {
    assertThat(send("POST", "/sim/ocs/scenario", "{\"forceMode\":\"UNKNOWN_CODE\",\"latencyMs\":10}").statusCode()).isEqualTo(200);
    JsonNode scenarios = json.readTree(send("GET", "/mock-control/ocs/scenarios", null).body());
    assertThat(scenarios.toString()).contains("BUSINESS_ERROR").contains("9876").contains("topup");
    assertThat(json.readTree(send("GET", "/mock-control/ocs/switch", null).body()).get("state").asString()).isEqualTo("SLOW");
    HttpResponse<String> res = topup("MC-alias", MSISDN, "100.00");
    assertThat(res.statusCode()).isEqualTo(422);
    assertThat(json.readTree(res.body()).get("code").asString()).isEqualTo("9876");
    assertThat(send("DELETE", "/sim/ocs/scenario", null).statusCode()).isEqualTo(204);
    assertThat(json.readTree(send("GET", "/mock-control/ocs/scenarios", null).body())).isEmpty();
  }
}
