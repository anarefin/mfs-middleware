package mfs.mw.simulators.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import mfs.mw.simulators.client.UssdGatewayClient;
import mfs.mw.simulators.dto.UssdDialRequest;
import mfs.mw.simulators.dto.UssdDialResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The simulated MNO against a stand-in mw-ussd: steps, wire format, stop on the end flag. */
class UssdGatewayServiceTest {

  static final JsonMapper JSON = JsonMapper.builder().build();

  HttpServer server;
  final List<JsonNode> received = new CopyOnWriteArrayList<>();
  final List<String> authorizations = new CopyOnWriteArrayList<>();
  final MockEnvironment env = new MockEnvironment();

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/ussd/robi", ex -> {
      JsonNode step = JSON.readTree(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      received.add(step);
      authorizations.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
      // ends on the third step
      String flag = received.size() >= 3 ? "END" : "CON";
      byte[] out = JSON.writeValueAsBytes(JSON.createObjectNode()
          .put("sessionId", step.path("sessionId").asString())
          .put("message", "screen " + received.size())
          .put("flag", flag));
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

  UssdGatewayService service() {
    return new UssdGatewayService(
        new UssdGatewayClient(JSON, env),
        new mfs.mw.simulators.client.UssdSmppGateway(
            new mfs.mw.simulators.config.UssdSmppGatewayProperties(false, 0, java.util.List.of("gp"), null)),
        "http://127.0.0.1:" + server.getAddress().getPort() + "/ussd", "END");
  }

  @Test
  void dialsThenSendsEachInputUntilTheSessionEnds() {
    UssdDialResult r =
        service().dial(new UssdDialRequest("robi", "8801712345678", "*268#", "s-1", List.of("1", "01812345678", "never sent"), false));

    assertThat(r.sessionId()).isEqualTo("s-1");
    assertThat(r.ended()).isTrue();
    assertThat(r.screens()).extracting(s -> s.message()).containsExactly("screen 1", "screen 2", "screen 3");
    assertThat(received).hasSize(3);
    assertThat(received.get(0).path("type").asString()).isEqualTo("BEGIN");
    assertThat(received.get(0).path("serviceCode").asString()).isEqualTo("*268#");
    assertThat(received.get(1).path("type").asString()).isEqualTo("CONTINUE");
    assertThat(received.get(1).path("input").asString()).isEqualTo("1");
    assertThat(received.get(2).path("input").asString()).isEqualTo("01812345678");
    assertThat(received).allSatisfy(s -> assertThat(s.path("msisdn").asString()).isEqualTo("8801712345678"));
  }

  @Test
  void aSessionWithoutIdGetsARandomOneAndStaysOpenWhenInputsRunOut() {
    UssdDialResult r = service().dial(new UssdDialRequest("robi", "01712345678", null, null, List.of(), false));

    assertThat(r.sessionId()).startsWith("sim-");
    assertThat(r.ended()).isFalse();
    assertThat(r.screens()).hasSize(1);
  }

  /** Ticket 39: a session left open by an earlier call is continued without a new BEGIN. */
  @Test
  void aResumedSessionSendsOnlyItsInputs() {
    UssdDialResult r = service().dial(new UssdDialRequest("robi", "01712345678", "*268#", "s-4", List.of("9"), true));

    assertThat(r.sessionId()).isEqualTo("s-4");
    assertThat(received).singleElement().satisfies(s -> {
      assertThat(s.path("type").asString()).isEqualTo("CONTINUE");
      assertThat(s.path("input").asString()).isEqualTo("9");
    });
    assertThat(r.screens()).extracting(s -> s.message()).containsExactly("screen 1");
  }

  /** Ticket 26 fix: APISIX's ussd-http-<mno> route takes basic-auth as consumer ussd_<mno>. */
  @Test
  void everyStepCarriesTheMnosBasicAuthCredential() {
    env.setProperty("sim.ussd-gateway.credentials.robi", "fake-robi-secret");

    service().dial(new UssdDialRequest("robi", "01712345678", "*268#", "s-2", List.of("1", "2"), false));

    String expected = "Basic " + java.util.Base64.getEncoder()
        .encodeToString("ussd-robi:fake-robi-secret".getBytes(StandardCharsets.UTF_8));
    assertThat(authorizations).hasSize(3).containsOnly(expected);
  }

  @Test
  void anMnoWithoutACredentialIsPlayedWithoutAuthorization() {
    service().dial(new UssdDialRequest("robi", "01712345678", "*268#", "s-3", List.of(), false));

    assertThat(authorizations).containsExactly("null");
  }

  @Test
  void anUnreachableUssdServiceIsReportedNotThrown() {
    server.stop(0);
    UssdDialResult r = service().dial(new UssdDialRequest("robi", "01712345678", null, null, List.of("1"), false));
    assertThat(r.screens()).singleElement().satisfies(s -> assertThat(s.status()).isEqualTo(-1));
    assertThat(r.ended()).isFalse();
  }
}
