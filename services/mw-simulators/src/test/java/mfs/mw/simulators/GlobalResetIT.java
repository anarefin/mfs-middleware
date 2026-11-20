package mfs.mw.simulators;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.concurrent.atomic.AtomicInteger;
import mfs.mw.simulators.service.mockcontrol.MockParties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The global reset (FRS MCT-07, ticket 103) over real HTTP: {@code POST /mock-control/reset} resets every
 * local party (journal, scenarios, switch, settings, kept state) and fans out to mw-core-mock's {@code POST
 * /mock-control/reset} (here a stub that answers like it), answering one line per party; an unreachable
 * core is reported FAILED, the local parties are still reset.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestInfrastructure.class)
class GlobalResetIT {

  private static final HttpServer CORE = core();
  private static final List<String> CORE_CALLS = new CopyOnWriteArrayList<>();
  private static final AtomicInteger CORE_STATUS = new AtomicInteger(200);

  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private final JsonMapper json = JsonMapper.builder().build();

  @LocalServerPort int port;

  private static HttpServer core() {
    try {
      HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      s.createContext("/mock-control/reset", ex -> {
        CORE_CALLS.add(ex.getRequestMethod() + " " + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        byte[] body = "{\"parties\":[{\"party\":\"core\",\"status\":\"RESET\",\"seedVersion\":\"v1\"}]}"
            .getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(CORE_STATUS.get(), body.length);
        ex.getResponseBody().write(body);
        ex.close();
      });
      s.start();
      return s;
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @DynamicPropertySource
  static void coreMock(DynamicPropertyRegistry r) {
    r.add("sim.core-mock.base-url", () -> "http://127.0.0.1:" + CORE.getAddress().getPort());
  }

  @AfterAll
  static void stop() {
    CORE.stop(0);
  }

  @BeforeEach
  void fresh() {
    CORE_CALLS.clear();
    CORE_STATUS.set(200);
  }

  private HttpResponse<String> send(String method, String path, String body) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(10));
    if (body == null) {
      b.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
    }
    return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void everyPartysJournalAndScenariosAreClearedAndTheCoreIsResetToo() throws Exception {
    send("POST", "/ocs/v1/topup", "{\"transactionId\":\"GR-1\",\"msisdn\":\"01712345678\",\"amount\":\"10.00\"}");
    assertThat(send("POST", "/mock-control/ocs/scenarios", "{\"behaviour\":\"TIMEOUT\"}").statusCode()).isEqualTo(201);
    assertThat(send("POST", "/mock-control/billagg/scenarios", "{\"behaviour\":\"HTTP_5XX\"}").statusCode()).isEqualTo(201);
    assertThat(send("PUT", "/mock-control/c2wbank/switch", "{\"state\":\"DOWN\"}").statusCode()).isEqualTo(200);
    // the SMPP and HSM parties of ticket 102b are reset too
    assertThat(send("POST", "/mock-control/smsc/scenarios",
        "{\"operation\":\"submit\",\"behaviour\":\"BUSINESS_ERROR\",\"code\":\"0x58\"}").statusCode()).isEqualTo(201);
    assertThat(send("POST", "/mock-control/ussdgw/scenarios",
        "{\"operation\":\"enquire_link\",\"behaviour\":\"TIMEOUT\"}").statusCode()).isEqualTo(201);
    assertThat(send("POST", "/mock-control/payshield/scenarios",
        "{\"operation\":\"command\",\"behaviour\":\"BUSINESS_ERROR\",\"code\":\"15\"}").statusCode()).isEqualTo(201);
    assertThat(send("PUT", "/mock-control/ussdgw/switch", "{\"state\":\"MAINTENANCE\"}").statusCode()).isEqualTo(200);
    assertThat(json.readTree(send("GET", "/mock-control/ocs/journal", null).body())).isNotEmpty();

    HttpResponse<String> res = send("POST", "/mock-control/reset", "{\"seedVersion\":\"v1\"}");

    assertThat(res.statusCode()).isEqualTo(200);
    JsonNode parties = json.readTree(res.body()).path("parties");
    List<String> names = new java.util.ArrayList<>();
    parties.forEach(p -> {
      names.add(p.path("party").asString());
      assertThat(p.path("status").asString()).as(p.toString()).isEqualTo("RESET");
    });
    List<String> expected = new java.util.ArrayList<>(MockParties.ALL.stream().map(p -> p.name()).toList());
    expected.add("core");
    assertThat(names).containsExactlyElementsOf(expected);
    // the push mock (ticket 110) and the file mocks (ticket 123) are reset too
    assertThat(names).contains("push", "aml", "trustbank", "dw");
    assertThat(CORE_CALLS).containsExactly("POST {\"seedVersion\":\"v1\"}");
    for (var p : MockParties.ALL) {
      assertThat(json.readTree(send("GET", "/mock-control/" + p.name() + "/journal", null).body())).as(p.name()).isEmpty();
      assertThat(json.readTree(send("GET", "/mock-control/" + p.name() + "/scenarios", null).body())).as(p.name()).isEmpty();
      assertThat(json.readTree(send("GET", "/mock-control/" + p.name() + "/switch", null).body()).path("state").asString())
          .isEqualTo("UP");
    }
  }

  @Test
  void aCoreThatCannotResetIsReportedAndTheLocalPartiesAreStillReset() throws Exception {
    CORE_STATUS.set(503);
    assertThat(send("POST", "/mock-control/ocs/scenarios", "{\"behaviour\":\"TIMEOUT\"}").statusCode()).isEqualTo(201);

    HttpResponse<String> res = send("POST", "/mock-control/reset", null);

    assertThat(res.statusCode()).isEqualTo(200);
    JsonNode core = null;
    for (JsonNode p : json.readTree(res.body()).path("parties")) {
      if ("core".equals(p.path("party").asString())) {
        core = p;
      }
    }
    assertThat(core).isNotNull();
    assertThat(core.path("status").asString()).isEqualTo("FAILED");
    assertThat(core.path("detail").asString()).contains("503");
    assertThat(CORE_CALLS).containsExactly("POST ");
    assertThat(json.readTree(send("GET", "/mock-control/ocs/scenarios", null).body())).isEmpty();
  }
}
