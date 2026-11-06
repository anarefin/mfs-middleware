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
 * The C2W bank simulator {@code c2wbank} (ticket 23, spec 22.2): a debit of a linked bank account
 * is accepted with 202 PENDING and its outcome is posted, signed hmac-sha256, to the scenario's
 * callback URL; magic values choose the outcome; the status inquiry reports the real outcome; a
 * callback can be replayed. A local HTTP server plays the middleware.
 */
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {"sim.timeout-delay=300ms", "sim.callback-secrets.c2wbank=" + BankC2wSimulatorIT.SECRET})
@Import(TestInfrastructure.class)
class BankC2wSimulatorIT {

  static final String SECRET = "fake-c2wbank-hmac-secret-for-tests";
  static final String CALLBACK_PATH = "/partner/c2wbank/callback/debits";

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
    send("DELETE", "/sim/c2wbank/scenario", null);
    send("DELETE", "/sim/c2wbank/requests", null);
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
            "/sim/c2wbank/scenario",
            "{\"callbackMode\":\"%s\",\"callbackUrl\":\"%s\",\"callbackDelayMs\":%d}"
                .formatted(callbackMode, callbackUrl(), delayMs));
    assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
  }

  private HttpResponse<String> debit(String id, String account, String amount) throws Exception {
    return send(
        "POST",
        "/c2wbank/v1/debits",
        "{\"transactionId\":\"%s\",\"accountNumber\":\"%s\",\"amount\":\"%s\"}".formatted(id, account, amount));
  }

  private List<Received> await(int count, Duration timeout) throws InterruptedException {
    long end = System.nanoTime() + timeout.toNanos();
    while (received.size() < count && System.nanoTime() < end) {
      Thread.sleep(20);
    }
    return List.copyOf(received);
  }

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

  private String statusOf(String id) throws Exception {
    return json.readTree(send("GET", "/c2wbank/v1/debits/" + id, null).body()).get("status").asString();
  }

  @Test
  void aDebitIsAcceptedAndItsSignedOutcomeFollowsByCallback() throws Exception {
    scenario("ONCE", 0);
    String id = id();

    HttpResponse<String> res = debit(id, "1234567890123", "500.00");

    assertThat(res.statusCode()).as(res.body()).isEqualTo(202);
    JsonNode accepted = json.readTree(res.body());
    assertThat(accepted.get("status").asString()).isEqualTo("PENDING");
    assertThat(accepted.get("debitRef").asString()).startsWith("C2W-");
    Received cb = await(1, Duration.ofSeconds(5)).getFirst();
    assertThat(cb.path()).isEqualTo(CALLBACK_PATH);
    JsonNode body = json.readTree(cb.body());
    assertThat(body.get("transactionId").asString()).isEqualTo(id);
    assertThat(body.get("status").asString()).isEqualTo("SUCCESS");
    assertThat(body.get("debitRef").asString()).isEqualTo(accepted.get("debitRef").asString());
    assertThat(cb.signature()).isEqualTo(expectedSignature(cb));
    assertThat(statusOf(id)).isEqualTo("SUCCESS");
    // a repeated debit is not applied twice: same reference, still accepted
    HttpResponse<String> again = debit(id, "1234567890123", "500.00");
    assertThat(again.statusCode()).isEqualTo(202);
    assertThat(json.readTree(again.body()).get("debitRef").asString()).isEqualTo(accepted.get("debitRef").asString());
  }

  @Test
  void anAccountEndingIn999IsRefusedByCallback() throws Exception {
    scenario("ONCE", 0);
    String id = id();

    assertThat(debit(id, "1234567890999", "500.00").statusCode()).isEqualTo(202);

    JsonNode body = json.readTree(await(1, Duration.ofSeconds(5)).getFirst().body());
    assertThat(body.get("status").asString()).isEqualTo("FAILED");
    assertThat(body.get("code").asString()).isEqualTo("4002");
    assertThat(statusOf(id)).isEqualTo("FAILED");
  }

  @Test
  void aTimeoutAppliesTheDebitHoldsTheAnswerAndSendsNoCallback() throws Exception {
    scenario("ONCE", 0);
    String id = id();
    long start = System.nanoTime();

    HttpResponse<String> res = debit(id, "1234567890123", "500.13");

    assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(300));
    assertThat(res.statusCode()).isEqualTo(202);
    assertThat(await(1, Duration.ofMillis(500))).isEmpty();
    assertThat(statusOf(id)).isEqualTo("SUCCESS");
  }

  @Test
  void anUnknownDebitIsNotFound() throws Exception {
    HttpResponse<String> res = send("GET", "/c2wbank/v1/debits/" + id(), null);
    assertThat(res.statusCode()).isEqualTo(404);
    assertThat(json.readTree(res.body()).get("status").asString()).isEqualTo("NOT_FOUND");
  }

  @Test
  void anOutageIsAnswered503AndNothingIsApplied() throws Exception {
    assertThat(send("POST", "/sim/c2wbank/scenario", "{\"outage\":true}").statusCode()).isEqualTo(200);
    String id = id();
    assertThat(debit(id, "1234567890123", "500.00").statusCode()).isEqualTo(503);
    send("DELETE", "/sim/c2wbank/scenario", null);
    assertThat(send("GET", "/c2wbank/v1/debits/" + id, null).statusCode()).isEqualTo(404);
  }

  @Test
  void anInvalidAccountNumberIsRefused400() throws Exception {
    assertThat(debit(id(), "12-34", "500.00").statusCode()).isEqualTo(400);
  }

  @Test
  void duplicateSendsTheSameCallbackTwice() throws Exception {
    scenario("DUPLICATE", 0);
    debit(id(), "1234567890123", "500.00");
    List<Received> got = await(2, Duration.ofSeconds(5));
    assertThat(got).hasSize(2);
    assertThat(got.get(1).body()).isEqualTo(got.get(0).body());
  }

  @Test
  void outOfOrderSendsTheOutcomeThenAStalePending() throws Exception {
    scenario("OUT_OF_ORDER", 0);
    debit(id(), "1234567890123", "500.00");
    List<Received> got = await(2, Duration.ofSeconds(5));
    assertThat(got).extracting(r -> json.readTree(r.body()).get("status").asString())
        .containsExactly("SUCCESS", "PENDING");
  }

  @Test
  void aDebitCallbackCanBeReplayedOnDemandAndRequestsAreRecorded() throws Exception {
    scenario("NONE", 0);
    String id = id();
    debit(id, "1234567890123", "500.00");
    assertThat(await(1, Duration.ofMillis(300))).isEmpty();

    HttpResponse<String> replay = send("POST", "/sim/c2wbank/callbacks/" + id + "/replay", null);

    assertThat(replay.statusCode()).as(replay.body()).isEqualTo(200);
    assertThat(json.readTree(replay.body()).get("targetStatus").asInt()).isEqualTo(200);
    Received cb = await(1, Duration.ofSeconds(5)).getFirst();
    assertThat(json.readTree(cb.body()).get("transactionId").asString()).isEqualTo(id);
    assertThat(cb.signature()).isEqualTo(expectedSignature(cb));
    JsonNode recorded = json.readTree(send("GET", "/sim/c2wbank/requests?path=/c2wbank/v1/debits", null).body());
    assertThat(recorded.size()).isEqualTo(1);
  }
}
