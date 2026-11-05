package mfs.mw.simulators.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import mfs.mw.simulators.client.UssdGatewayClient;
import mfs.mw.simulators.dto.UssdScreen;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The API this module calls ({@code consumes.apis} of {@code contracts.yml}, ticket 53): the
 * simulated MNO gateway's {@code client/UssdGatewayClient} posts steps to mw-ussd's {@code
 * api/mw-ussd/ussd-http.v1}. A local stand-in (JDK HTTP server, no Docker) records what the real
 * client sends, which must match {@code UssdStep}, and answers a {@code UssdScreen} (validated
 * against the contract first) that the client must read. The client builds its body as a JSON tree
 * with the Boot-built JsonMapper ({@code findAndAddModules()} on the Jackson 3 defaults).
 *
 * <p>mw-simulators publishes and consumes no events (the Kafka chassis is copied but unused: no
 * outbox writes, no consumed topic) and serves no contract API (its partner wire formats and control
 * API are simulator-owned, not in {@code contracts/}); {@link #manifestListsOnlyTheUssdApi} keeps the
 * manifest honest.
 */
class ApiContractsTest {

  static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();
  static final String USSD = "api/mw-ussd/ussd-http.v1.openapi.yaml";

  HttpServer server;
  final List<JsonNode> received = new CopyOnWriteArrayList<>();
  final MockEnvironment env = new MockEnvironment().withProperty(UssdGatewayClient.CREDENTIALS + "robi", "fake-secret");

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/ussd/robi", ex -> {
      JsonNode step = JSON.readTree(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      received.add(step);
      JsonNode screen = JSON.createObjectNode()
          .put("sessionId", step.path("sessionId").asString())
          .put("message", "1. Send money\n2. Cash out")
          .put("flag", "CON");
      assertThat(OpenApiSchemas.component(USSD, "UssdScreen").validate(screen)).as("stand-in answer").isEmpty();
      byte[] out = JSON.writeValueAsBytes(screen);
      ex.getResponseHeaders().add("Content-Type", "application/json");
      ex.sendResponseHeaders(200, out.length);
      ex.getResponseBody().write(out);
      ex.close();
    });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/ussd/robi";
  }

  @Test
  void everyStepTheClientSendsMatchesUssdStepAndTheScreenIsRead() {
    UssdGatewayClient client = new UssdGatewayClient(JSON, env);

    UssdScreen begin = client.step("robi", url(), "s-1", "8801712345678", "*268#", "BEGIN", null);
    UssdScreen next = client.step("robi", url(), "s-1", "01712345678", null, "CONTINUE", "1");
    client.step("robi", url(), "s-1", "+8801712345678", "*268#", "END", "");

    assertThat(received).hasSize(3);
    for (JsonNode step : received) {
      assertThat(OpenApiSchemas.component(USSD, "UssdStep").validate(step)).as("%s", step).isEmpty();
    }
    assertThat(received.get(1).has("serviceCode")).isFalse();
    assertThat(begin.status()).isEqualTo(200);
    assertThat(begin.message()).isEqualTo("1. Send money\n2. Cash out");
    assertThat(begin.flag()).isEqualTo("CON");
    assertThat(next.flag()).isEqualTo("CON");
  }

  @Test
  void theStepSchemaIsReallyApplied() {
    JsonNode noType = JSON.createObjectNode().put("sessionId", "s-1").put("msisdn", "01712345678");
    assertThat(OpenApiSchemas.component(USSD, "UssdStep").validate(noType)).isNotEmpty();
  }

  @Test
  void manifestListsOnlyTheUssdApi() {
    ContractManifest m = ContractManifest.load();
    assertThat(m.producedEvents()).as("a produced event needs a ProducedContractsTest").isEmpty();
    assertThat(m.consumedEvents()).as("a consumed event needs a ConsumedContractsTest").isEmpty();
    assertThat(m.producedApis()).isEmpty();
    assertThat(m.consumedApis()).containsExactly(USSD);
  }
}
