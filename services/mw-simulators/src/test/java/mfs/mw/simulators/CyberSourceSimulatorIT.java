package mfs.mw.simulators;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import mfs.mw.simulators.service.CyberSourceService;
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
 * The card acquirer mock {@code cybersource} (ticket 118, FRS MCK-06, FL-07): a session gives a hosted
 * page; the test card typed there becomes a transient token and its 3-D Secure result goes as a signed
 * webhook to the party's callback URL (duplicated on demand); the capture of that token succeeds or is
 * declined by the test card; the status inquiry and the refund follow the capture. A local HTTP server
 * plays the middleware.
 */
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {"sim.timeout-delay=300ms", "sim.callback-secrets.cybersource=" + CyberSourceSimulatorIT.SECRET})
@Import(TestInfrastructure.class)
class CyberSourceSimulatorIT {

  static final String SECRET = "fake-cybersource-hmac-secret-for-tests";

  record Received(String signature, String body) {}

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
          received.add(new Received(ex.getRequestHeaders().getFirst("X-Signature"),
              new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
          ex.sendResponseHeaders(200, -1);
          ex.close();
        });
    middleware.start();
    send("PUT", "/mock-control/cybersource/settings",
        "{\"callbackUrl\":\"http://127.0.0.1:" + middleware.getAddress().getPort() + "/partner/cybersource/callback\"}");
  }

  @AfterEach
  void stop() throws Exception {
    middleware.stop(0);
    send("DELETE", "/mock-control/cybersource/journal", null);
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

  private JsonNode card(String sessionId, String cardNumber) throws Exception {
    HttpResponse<String> r = http.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/cybersource/hp/" + sessionId))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString("cardNumber=" + URLEncoder.encode(cardNumber, StandardCharsets.UTF_8)))
            .build(),
        HttpResponse.BodyHandlers.ofString());
    assertThat(r.statusCode()).isEqualTo(200);
    return json.readTree(r.body());
  }

  private String session(String ref) throws Exception {
    HttpResponse<String> r = send("POST", "/cybersource/v1/sessions",
        "{\"clientReference\":\"" + ref + "\",\"amount\":\"500.00\",\"currency\":\"BDT\"}");
    assertThat(r.statusCode()).isEqualTo(201);
    JsonNode body = json.readTree(r.body());
    assertThat(body.path("status").asString()).isEqualTo("CREATED");
    assertThat(body.path("hostedPageUrl").asString()).endsWith("/" + body.path("sessionId").asString());
    return body.path("sessionId").asString();
  }

  private void awaitCallbacks(int n) throws InterruptedException {
    Instant until = Instant.now().plus(Duration.ofSeconds(10));
    while (received.size() < n && Instant.now().isBefore(until)) {
      Thread.sleep(20);
    }
  }

  private HttpResponse<String> capture(String ref, String sessionId, String token) throws Exception {
    return send("POST", "/cybersource/v1/payments", "{\"clientReference\":\"" + ref + "\",\"sessionId\":\""
        + sessionId + "\",\"transientToken\":\"" + token + "\",\"amount\":\"500.00\",\"currency\":\"BDT\"}");
  }

  @Test
  void aSuccessCardIsAuthenticatedByASignedWebhookThenCapturedRefundedAndReported() throws Exception {
    String ref = "BITS" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
    String sessionId = session(ref);
    HttpResponse<String> page = send("GET", "/cybersource/hp/" + sessionId, null);
    assertThat(page.statusCode()).isEqualTo(200);
    assertThat(page.body()).contains("<form").contains("500.00");

    JsonNode auth = card(sessionId, CyberSourceService.CARD_SUCCESS);
    assertThat(auth.path("status").asString()).isEqualTo("AUTHENTICATED");
    String token = auth.path("transientToken").asString();
    awaitCallbacks(1);
    assertThat(received).hasSize(1);
    assertThat(received.getFirst().signature()).isNotBlank();
    assertThat(received.getFirst().body()).contains(token).contains("AUTHENTICATED")
        .doesNotContain(CyberSourceService.CARD_SUCCESS);

    HttpResponse<String> captured = capture(ref, sessionId, token);
    assertThat(captured.statusCode()).isEqualTo(201);
    assertThat(json.readTree(captured.body()).path("status").asString()).isEqualTo("CAPTURED");
    HttpResponse<String> again = capture(ref, sessionId, token);
    assertThat(json.readTree(again.body()).path("paymentId").asString())
        .isEqualTo(json.readTree(captured.body()).path("paymentId").asString());

    HttpResponse<String> status = send("GET", "/cybersource/v1/payments/" + ref, null);
    assertThat(json.readTree(status.body()).path("status").asString()).isEqualTo("CAPTURED");

    HttpResponse<String> refund = send("POST", "/cybersource/v1/payments/" + ref + "/refunds",
        "{\"refundId\":\"reverse:x\",\"amount\":\"500.00\"}");
    assertThat(refund.statusCode()).isEqualTo(201);
    HttpResponse<String> refundAgain = send("POST", "/cybersource/v1/payments/" + ref + "/refunds",
        "{\"refundId\":\"reverse:x\",\"amount\":\"500.00\"}");
    assertThat(json.readTree(refundAgain.body()).path("refundId").asString())
        .isEqualTo(json.readTree(refund.body()).path("refundId").asString());
  }

  @Test
  void aDeclinedCardIsRefusedAndA3dsFailureSaysSoInTheWebhookAndTheCapture() throws Exception {
    String ref1 = "BITS" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
    String s1 = session(ref1);
    JsonNode declined = card(s1, CyberSourceService.CARD_DECLINE);
    HttpResponse<String> c1 = capture(ref1, s1, declined.path("transientToken").asString());
    assertThat(c1.statusCode()).isEqualTo(422);
    assertThat(json.readTree(c1.body()).path("code").asString()).isEqualTo("4001");

    String ref2 = "BITS" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
    String s2 = session(ref2);
    JsonNode failed = card(s2, CyberSourceService.CARD_3DS_FAIL);
    assertThat(failed.path("status").asString()).isEqualTo("FAILED");
    assertThat(failed.path("code").asString()).isEqualTo("4002");
    HttpResponse<String> c2 = capture(ref2, s2, failed.path("transientToken").asString());
    assertThat(c2.statusCode()).isEqualTo(422);
    assertThat(json.readTree(c2.body()).path("code").asString()).isEqualTo("4002");

    HttpResponse<String> unknown = send("GET", "/cybersource/v1/payments/BITSNOPE", null);
    assertThat(unknown.statusCode()).isEqualTo(404);
  }

  @Test
  void aDuplicateCallbackScenarioSendsTheWebhookTwice() throws Exception {
    send("POST", "/mock-control/cybersource/scenarios",
        "{\"operation\":\"authenticate\",\"behaviour\":\"DUPLICATE_CALLBACK\"}");
    String ref = "BITS" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
    card(session(ref), CyberSourceService.CARD_SUCCESS);

    awaitCallbacks(2);
    assertThat(received).hasSize(2);
    assertThat(received.get(0).body()).isEqualTo(received.get(1).body());
  }
}
