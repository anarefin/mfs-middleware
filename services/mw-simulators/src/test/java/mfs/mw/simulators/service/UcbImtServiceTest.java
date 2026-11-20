package mfs.mw.simulators.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import mfs.mw.simulators.client.CallbackClient;
import mfs.mw.simulators.client.CallbackSigner;
import mfs.mw.simulators.config.SimulatorProperties;
import mfs.mw.simulators.dto.MockBehaviour;
import mfs.mw.simulators.dto.MockScenario;
import mfs.mw.simulators.dto.UcbImtRequests;
import mfs.mw.simulators.service.mockcontrol.PartySwitch;
import mfs.mw.simulators.service.mockcontrol.ScenarioEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * UCB IMT pushes a signed credit (ticket 119, MCK-06): once by default, twice for a duplicate
 * scenario, after a wait for a late one, and not at all for no-callback. The confirmation the
 * adapter posts is then visible to a status inquiry.
 */
class UcbImtServiceTest {

  static final String SECRET = "dev-ucb-imt-hmac-secret";
  static final JsonMapper JSON = JsonMapper.builder().build();
  static final UcbImtRequests.Trigger TRIGGER = new UcbImtRequests.Trigger("2500.50", "01711000001", "Ada Lovelace", "AE");

  HttpServer middleware;
  final List<Hit> hits = new CopyOnWriteArrayList<>();
  ScenarioEngine engine;
  UcbImtService service;

  @BeforeEach
  void setUp() throws Exception {
    middleware = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    middleware.createContext("/remit/v1/credits", exchange -> {
      byte[] body = exchange.getRequestBody().readAllBytes();
      hits.add(new Hit(new String(body, StandardCharsets.UTF_8), Map.of(
          "signature", header(exchange, "X-Signature"),
          "timestamp", header(exchange, "X-Timestamp"),
          "nonce", header(exchange, "X-Nonce"),
          "partner", header(exchange, "X-Partner-Id"))));
      exchange.sendResponseHeaders(202, -1);
      exchange.close();
    });
    middleware.start();
    engine = new ScenarioEngine(new PartySwitch(), new SimulatorProperties(Duration.ofMillis(10), 50), new SplittableRandom(1));
    MockEnvironment env = new MockEnvironment().withProperty(CallbackSigner.PREFIX + UcbImtService.PARTNER, SECRET);
    service = new UcbImtService(
        engine,
        new CallbackClient(),
        new CallbackSigner(env, Clock.systemUTC()),
        JSON,
        "http://127.0.0.1:" + middleware.getAddress().getPort() + "/remit/v1/credits");
  }

  @AfterEach
  void tearDown() {
    middleware.stop(0);
  }

  @Test
  void aTriggerPostsOneSignedCredit() throws Exception {
    UcbImtRequests.TriggerResult result = service.trigger(TRIGGER);

    assertThat(result.copies()).isEqualTo(1);
    assertThat(result.targetStatus()).isEqualTo(202);
    assertThat(hits).hasSize(1);
    Hit hit = hits.getFirst();
    JsonNode body = JSON.readTree(hit.body());
    assertThat(body.path("partnerRef").asString()).isEqualTo(result.partnerRef());
    assertThat(body.path("beneficiaryMsisdn").asString()).isEqualTo("01711000001");
    assertThat(body.path("amount").asString()).isEqualTo("2500.50");
    assertThat(body.path("currency").asString()).isEqualTo("BDT");
    assertThat(body.path("senderName").asString()).isEqualTo("Ada Lovelace");
    assertThat(body.path("senderCountry").asString()).isEqualTo("AE");
    assertThat(hit.headers().get("partner")).isEqualTo("ucb-imt");
    assertThat(hit.headers().get("signature")).isEqualTo(sign(hit));
  }

  @Test
  void aDuplicateScenarioSendsTheSameCreditTwiceWithFreshNonces() {
    engine.add(UcbImtService.PARTNER, MockScenario.of("trigger", MockBehaviour.DUPLICATE_CALLBACK));

    UcbImtRequests.TriggerResult result = service.trigger(TRIGGER);

    assertThat(result.copies()).isEqualTo(2);
    assertThat(hits).hasSize(2);
    assertThat(hits.get(0).body()).isEqualTo(hits.get(1).body());
    assertThat(hits.get(0).headers().get("nonce")).isNotEqualTo(hits.get(1).headers().get("nonce"));
  }

  @Test
  void aLateScenarioWaitsBeforeSending() {
    engine.add(UcbImtService.PARTNER, MockScenario.of("trigger", MockBehaviour.LATE_CALLBACK).withDelayMs(80L));

    long started = System.nanoTime();
    service.trigger(TRIGGER);

    assertThat(hits).hasSize(1);
    assertThat(System.nanoTime() - started).isGreaterThanOrEqualTo(Duration.ofMillis(70).toNanos());
  }

  @Test
  void aNoCallbackScenarioSendsNothing() {
    engine.add(UcbImtService.PARTNER, MockScenario.of("trigger", MockBehaviour.NO_CALLBACK));

    UcbImtRequests.TriggerResult result = service.trigger(TRIGGER);

    assertThat(result.copies()).isZero();
    assertThat(result.targetStatus()).isEqualTo(-1);
    assertThat(hits).isEmpty();
  }

  @Test
  void aConfirmationIsThenReportedConfirmed() {
    var reply = service.confirm("BITSABC", new UcbImtRequests.Confirmation("BITSABC", "01933b8e-7c1a-7b2e-9f3d-2a4c5e6f7a8d", "CREDITED"), true);
    var again = service.confirm("BITSABC", new UcbImtRequests.Confirmation("BITSABC", "other-ref", "REJECTED"), true);
    var status = service.status("BITSABC", true);
    var missing = service.status("BITSNOPE", true);
    var unsigned = service.confirm("BITSABC", new UcbImtRequests.Confirmation("BITSABC", "x", "CREDITED"), false);

    assertThat(reply.httpStatus()).isEqualTo(200);
    assertThat(again.httpStatus()).isEqualTo(200);
    assertThat(stringBody(again).path("upayRef").asString()).isEqualTo("01933b8e-7c1a-7b2e-9f3d-2a4c5e6f7a8d");
    assertThat(status.httpStatus()).isEqualTo(200);
    assertThat(stringBody(status).path("confirmed").asBoolean()).isTrue();
    assertThat(missing.httpStatus()).isEqualTo(404);
    assertThat(stringBody(missing).path("code").asString()).isEqualTo("4001");
    assertThat(unsigned.httpStatus()).isEqualTo(401);
  }

  private static JsonNode stringBody(mfs.mw.simulators.dto.SimReply reply) {
    return JSON.valueToTree(reply.body());
  }

  private static String header(com.sun.net.httpserver.HttpExchange exchange, String name) {
    String v = exchange.getRequestHeaders().getFirst(name);
    return v == null ? "" : v;
  }

  /** The hmac-sha256 string the adapter verifies: method, path, timestamp, nonce, hex SHA-256 of the body. */
  private static String sign(Hit hit) throws Exception {
    String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(hit.body().getBytes(StandardCharsets.UTF_8)));
    String toSign = "POST\n/remit/v1/credits\n" + hit.headers().get("timestamp") + "\n" + hit.headers().get("nonce") + "\n" + hash;
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return Base64.getEncoder().encodeToString(mac.doFinal(toSign.getBytes(StandardCharsets.UTF_8)));
  }

  record Hit(String body, Map<String, String> headers) {}
}
