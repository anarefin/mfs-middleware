package mfs.mw.simulators;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The aggregator's callback-only mode and its callbacks (ticket 22, spec 22.2): a payment is
 * accepted with 202 PENDING and its outcome is posted, signed hmac-sha256 with the aggregator's
 * secret, to the scenario's callback URL once, twice (duplicate), late, or final-then-stale
 * (out of order); a callback can be replayed on demand. A local HTTP server plays the middleware.
 */
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {"sim.timeout-delay=300ms", "sim.callback-secrets.billagg=" + BillAggregatorCallbackIT.SECRET})
@Import(TestInfrastructure.class)
class BillAggregatorCallbackIT {

  static final String SECRET = "fake-billagg-hmac-secret-for-tests";
  static final String CALLBACK_PATH = "/partner/billagg/callback/payments";

  record Received(String path, String signature, String timestamp, String body) {}

  private final HttpClient http = HttpClient.newHttpClient();
  private final JsonMapper json = JsonMapper.builder().build();
  private final List<Received> received = new CopyOnWriteArrayList<>();
  private HttpServer middleware;

  @LocalServerPort int port;

  @BeforeEach
  void startMiddleware() throws Exception {
    middleware = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    middleware.createContext(
        "/",
        ex -> {
          received.add(
              new Received(
                  ex.getRequestURI().getRawPath(),
                  ex.getRequestHeaders().getFirst("X-Signature"),
                  ex.getRequestHeaders().getFirst("X-Timestamp"),
                  new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
          ex.sendResponseHeaders(200, -1);
          ex.close();
        });
    middleware.start();
  }

  @AfterEach
  void stop() throws Exception {
    middleware.stop(0);
    send("DELETE", "/sim/billagg/scenario", null);
  }

  private String callbackUrl() {
    return "http://127.0.0.1:" + middleware.getAddress().getPort() + CALLBACK_PATH;
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

  private void scenario(String callbackMode, long delayMs) throws Exception {
    HttpResponse<String> r =
        send(
            "POST",
            "/sim/billagg/scenario",
            "{\"callbackOnly\":true,\"callbackMode\":\"%s\",\"callbackUrl\":\"%s\",\"callbackDelayMs\":%d}"
                .formatted(callbackMode, callbackUrl(), delayMs));
    assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
  }

  private HttpResponse<String> pay(String id, String bill) throws Exception {
    return send(
        "POST",
        "/billagg/v1/payments",
        "{\"transactionId\":\"%s\",\"billerCode\":\"DESCO\",\"billNumber\":\"%s\",\"amount\":\"667.00\"}"
            .formatted(id, bill));
  }

  private List<Received> await(int count, Duration timeout) throws InterruptedException {
    long end = System.nanoTime() + timeout.toNanos();
    while (received.size() < count && System.nanoTime() < end) {
      Thread.sleep(20);
    }
    return List.copyOf(received);
  }

  /** The documented string to sign: method, path, timestamp, hex SHA-256 of the body. */
  private static String expectedSignature(Received r) throws Exception {
    String bodyHash =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(r.body().getBytes(StandardCharsets.UTF_8)));
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    String toSign = "POST\n" + r.path() + "\n" + r.timestamp() + "\n" + bodyHash;
    return Base64.getEncoder().encodeToString(mac.doFinal(toSign.getBytes(StandardCharsets.UTF_8)));
  }

  private static String id() {
    return "BITS" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
  }

  @Test
  void aCallbackOnlyPaymentIsAcceptedAndItsSignedOutcomeFollows() throws Exception {
    scenario("ONCE", 0);
    String id = id();

    HttpResponse<String> res = pay(id, "1234567");

    assertThat(res.statusCode()).isEqualTo(202);
    JsonNode accepted = json.readTree(res.body());
    assertThat(accepted.get("status").asString()).isEqualTo("PENDING");
    assertThat(accepted.get("paymentRef").asString()).startsWith("AGG-");
    List<Received> got = await(1, Duration.ofSeconds(5));
    assertThat(got).hasSize(1);
    Received cb = got.getFirst();
    assertThat(cb.path()).isEqualTo(CALLBACK_PATH);
    JsonNode body = json.readTree(cb.body());
    assertThat(body.get("transactionId").asString()).isEqualTo(id);
    assertThat(body.get("status").asString()).isEqualTo("SUCCESS");
    assertThat(body.get("paymentRef").asString()).isEqualTo(accepted.get("paymentRef").asString());
    assertThat(cb.signature()).isEqualTo(expectedSignature(cb));
    // the status inquiry knows the real outcome; a repeated payment is accepted again
    assertThat(json.readTree(send("GET", "/billagg/v1/payments/" + id, null).body()).get("status").asString())
        .isEqualTo("SUCCESS");
    assertThat(pay(id, "1234567").statusCode()).isEqualTo(202);
  }

  @Test
  void aRefusedCallbackOnlyPaymentCallsBackAFailure() throws Exception {
    scenario("ONCE", 0);

    assertThat(pay(id(), "1234999").statusCode()).isEqualTo(202);

    JsonNode body = json.readTree(await(1, Duration.ofSeconds(5)).getFirst().body());
    assertThat(body.get("status").asString()).isEqualTo("FAILED");
    assertThat(body.get("code").asString()).isEqualTo("3003");
  }

  @Test
  void duplicateSendsTheSameCallbackTwice() throws Exception {
    scenario("DUPLICATE", 0);

    pay(id(), "1234567");

    List<Received> got = await(2, Duration.ofSeconds(5));
    assertThat(got).hasSize(2);
    assertThat(got.get(1).body()).isEqualTo(got.get(0).body());
  }

  @Test
  void outOfOrderSendsTheOutcomeThenAStalePending() throws Exception {
    scenario("OUT_OF_ORDER", 0);

    pay(id(), "1234567");

    List<Received> got = await(2, Duration.ofSeconds(5));
    assertThat(got).extracting(r -> json.readTree(r.body()).get("status").asString())
        .containsExactly("SUCCESS", "PENDING");
    assertThat(got.get(1).signature()).isEqualTo(expectedSignature(got.get(1)));
  }

  @Test
  void lateWaitsForTheDelay() throws Exception {
    scenario("LATE", 400);

    pay(id(), "1234567");

    assertThat(await(1, Duration.ofMillis(200))).isEmpty();
    assertThat(await(1, Duration.ofSeconds(5))).hasSize(1);
  }

  @Test
  void aPaymentCallbackCanBeReplayedOnDemand() throws Exception {
    scenario("NONE", 0);
    String id = id();
    pay(id, "1234567");
    assertThat(await(1, Duration.ofMillis(300))).isEmpty();

    HttpResponse<String> replay = send("POST", "/sim/billagg/callbacks/" + id + "/replay", null);

    assertThat(replay.statusCode()).as(replay.body()).isEqualTo(200);
    assertThat(json.readTree(replay.body()).get("targetStatus").asInt()).isEqualTo(200);
    Received cb = await(1, Duration.ofSeconds(5)).getFirst();
    assertThat(json.readTree(cb.body()).get("transactionId").asString()).isEqualTo(id);
    assertThat(cb.signature()).isEqualTo(expectedSignature(cb));
  }
}
