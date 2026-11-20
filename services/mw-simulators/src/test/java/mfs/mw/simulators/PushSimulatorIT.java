package mfs.mw.simulators;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The push server mock {@code push} (ticket 110, FRS EXT-04): an FCM/APNs style app-server push API that
 * stores every message it accepts ({@code GET /push/v1/messages?token=}), behind an API key, on the
 * mock-control engine (SUCCESS, HTTP_5XX, TIMEOUT, an invalid token by magic value or BUSINESS_ERROR).
 */
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {"sim.timeout-delay=300ms", "sim.push.api-key=" + PushSimulatorIT.API_KEY})
@Import(TestInfrastructure.class)
class PushSimulatorIT {

  static final String API_KEY = "fake-push-api-key-for-tests";

  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private final JsonMapper json = JsonMapper.builder().build();

  @LocalServerPort int port;

  @AfterEach
  void reset() throws Exception {
    send("DELETE", "/mock-control/push/journal", null, null);
  }

  private HttpResponse<String> send(String method, String path, String body, String apiKey) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
        .timeout(Duration.ofSeconds(10));
    if (apiKey != null) {
      b.header("Authorization", "key=" + apiKey);
    }
    if (body == null) {
      b.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
    }
    return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> push(String token, String title, String body) throws Exception {
    return send("POST", "/push/v1/messages",
        "{\"messageId\":\"m-1\",\"token\":\"%s\",\"platform\":\"ANDROID\",\"notification\":{\"title\":\"%s\",\"body\":\"%s\"}}"
            .formatted(token, title, body),
        API_KEY);
  }

  private JsonNode stored(String token) throws Exception {
    HttpResponse<String> r = send("GET", "/push/v1/messages?token=" + token, null, null);
    assertThat(r.statusCode()).isEqualTo(200);
    return json.readTree(r.body());
  }

  @Test
  void aPushIsAcceptedDeliveredAndStoredWithItsText() throws Exception {
    HttpResponse<String> r = push("tok-aaa111", "Payment complete", "You sent Tk 50.00");

    assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
    JsonNode ack = json.readTree(r.body());
    assertThat(ack.path("status").asString()).isEqualTo("DELIVERED");
    assertThat(ack.path("messageId").asString()).startsWith("push-");
    JsonNode messages = stored("tok-aaa111");
    assertThat(messages).hasSize(1);
    assertThat(messages.get(0).path("title").asString()).isEqualTo("Payment complete");
    assertThat(messages.get(0).path("body").asString()).isEqualTo("You sent Tk 50.00");
    assertThat(messages.get(0).path("platform").asString()).isEqualTo("ANDROID");
    assertThat(messages.get(0).path("messageId").asString()).isEqualTo(ack.path("messageId").asString());
    assertThat(stored("tok-other")).isEmpty();
    // the same inbox on the control path (test-only APISIX route /test/sim/*)
    assertThat(json.readTree(send("GET", "/sim/push/messages?token=tok-aaa111", null, null).body())).hasSize(1);
  }

  @Test
  void aCallWithoutTheApiKeyIsRefused() throws Exception {
    HttpResponse<String> r = send("POST", "/push/v1/messages",
        "{\"token\":\"tok-aaa111\",\"notification\":{\"title\":\"t\",\"body\":\"b\"}}", "wrong-key");

    assertThat(r.statusCode()).isEqualTo(401);
    assertThat(stored("tok-aaa111")).isEmpty();
  }

  @Test
  void aTokenWithoutTargetIsRefused() throws Exception {
    HttpResponse<String> r = send("POST", "/push/v1/messages", "{\"notification\":{\"title\":\"t\",\"body\":\"b\"}}", API_KEY);

    assertThat(r.statusCode()).isEqualTo(400);
  }

  @Test
  void anInvalidTokenIsUnregisteredByMagicValue() throws Exception {
    HttpResponse<String> r = push("invalid-tok-1", "t", "b");

    assertThat(r.statusCode()).isEqualTo(404);
    assertThat(json.readTree(r.body()).path("error").asString()).isEqualTo("UNREGISTERED");
    assertThat(stored("invalid-tok-1")).isEmpty();
  }

  @Test
  void aBusinessErrorScenarioRefusesTheTokenWithItsCode() throws Exception {
    assertThat(send("POST", "/mock-control/push/scenarios",
        "{\"operation\":\"send\",\"behaviour\":\"BUSINESS_ERROR\",\"key\":\"tok-bbb222\",\"code\":\"INVALID_ARGUMENT\"}", null)
        .statusCode()).isEqualTo(201);

    HttpResponse<String> r = push("tok-bbb222", "t", "b");

    assertThat(r.statusCode()).isEqualTo(404);
    assertThat(json.readTree(r.body()).path("error").asString()).isEqualTo("INVALID_ARGUMENT");
    assertThat(push("tok-ccc333", "t", "b").statusCode()).isEqualTo(200);
  }

  @Test
  void anHttp5xxScenarioAnswersTheStatusAndStoresNothing() throws Exception {
    send("POST", "/mock-control/push/scenarios", "{\"behaviour\":\"HTTP_5XX\",\"httpStatus\":503}", null);

    HttpResponse<String> r = push("tok-ddd444", "t", "b");

    assertThat(r.statusCode()).isEqualTo(503);
    assertThat(stored("tok-ddd444")).isEmpty();
  }

  @Test
  void aTimeoutScenarioStoresThenHoldsTheAnswer() throws Exception {
    send("POST", "/mock-control/push/scenarios", "{\"behaviour\":\"TIMEOUT\",\"delayMs\":400}", null);

    long start = System.nanoTime();
    HttpResponse<String> r = push("tok-eee555", "t", "b");

    assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(400));
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(stored("tok-eee555")).hasSize(1);
  }

  @Test
  void aPartialSuccessIsOnlyAccepted() throws Exception {
    send("POST", "/mock-control/push/scenarios", "{\"behaviour\":\"PARTIAL_SUCCESS\"}", null);

    HttpResponse<String> r = push("tok-fff666", "t", "b");

    assertThat(json.readTree(r.body()).path("status").asString()).isEqualTo("ACCEPTED");
  }

  @Test
  void aDownSwitchClosesTheConnection() throws Exception {
    send("PUT", "/mock-control/push/switch", "{\"state\":\"DOWN\"}", null);

    assertThatThrownBy(() -> push("tok-ggg777", "t", "b")).isInstanceOf(IOException.class);
    assertThat(stored("tok-ggg777")).isEmpty();
  }

  @Test
  void thePartyResetForgetsTheStoredMessagesAndTheJournalHasTheCallWithoutTheKey() throws Exception {
    push("tok-hhh888", "t", "b");
    JsonNode journal = json.readTree(send("GET", "/mock-control/push/journal", null, null).body());
    assertThat(journal).isNotEmpty();
    assertThat(journal.toString()).doesNotContain(API_KEY);

    send("DELETE", "/mock-control/push/journal", null, null);

    assertThat(stored("tok-hhh888")).isEmpty();
  }

  @Test
  void aCallerCanAskForTheBehavioursOfThePushParty() throws Exception {
    assertThat(send("POST", "/mock-control/push/scenarios", "{\"behaviour\":\"DUPLICATE_CALLBACK\"}", null).statusCode())
        .isEqualTo(400);
  }
}
