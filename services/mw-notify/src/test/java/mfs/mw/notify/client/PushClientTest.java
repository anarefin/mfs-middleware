package mfs.mw.notify.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import mfs.mw.notify.config.PushProperties;
import mfs.mw.notify.dto.PushMessage;
import mfs.mw.notify.dto.PushResult;
import mfs.mw.notify.exception.PushRefusedException;
import mfs.mw.notify.exception.PushSendException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link PushClient} against a local stand-in push server (no Spring, no Docker): retries a 5xx or a
 * timeout with exponential backoff, never a refusal (4xx), sends the API key, reads the acknowledgement.
 */
class PushClientTest {

  private static final String KEY = "fake-push-key-for-tests";

  record Call(long atNanos, String authorization, String body) {}

  private final Deque<int[]> answers = new ArrayDeque<>();
  private final List<Call> calls = new CopyOnWriteArrayList<>();
  private HttpServer server;
  private PushClient client;

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/push/v1/messages", ex -> {
      calls.add(new Call(System.nanoTime(), ex.getRequestHeaders().getFirst("Authorization"),
          new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
      int[] a = answers.isEmpty() ? new int[] {200, 0} : answers.removeFirst();
      if (a[1] > 0) {
        try {
          Thread.sleep(a[1]);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      byte[] body = (a[0] == 200 ? "{\"messageId\":\"push-7\",\"status\":\"DELIVERED\"}"
          : a[0] == 404 ? "{\"error\":\"UNREGISTERED\"}" : "{\"error\":\"INTERNAL\"}").getBytes(StandardCharsets.UTF_8);
      ex.getResponseHeaders().add("Content-Type", "application/json");
      ex.sendResponseHeaders(a[0], body.length);
      ex.getResponseBody().write(body);
      ex.close();
    });
    server.start();
    RetryConfig retry = RetryConfig.custom()
        .maxAttempts(3)
        .intervalFunction(IntervalFunction.ofExponentialBackoff(Duration.ofMillis(100), 2))
        .ignoreExceptions(PushRefusedException.class)
        .build();
    PushProperties props = new PushProperties(true, "http://127.0.0.1:" + server.getAddress().getPort(), KEY,
        Duration.ofSeconds(1), Duration.ofMillis(300));
    client = new PushClient(props, RetryRegistry.of(retry),
        CircuitBreakerRegistry.of(CircuitBreakerConfig.custom().ignoreExceptions(PushRefusedException.class).build()));
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private static PushMessage message() {
    return new PushMessage("m-1", "tok-device-abc123", "ANDROID", "Payment complete", "You sent Tk 50.00", "txn-1");
  }

  @Test
  void aDeliveredPushIsAcknowledgedWithTheProviderIdAndCarriesTheKeyAndText() throws Exception {
    PushResult r = client.send(message());

    assertThat(r.providerMessageId()).isEqualTo("push-7");
    assertThat(r.delivered()).isTrue();
    assertThat(calls).hasSize(1);
    assertThat(calls.getFirst().authorization()).isEqualTo("key=" + KEY);
    assertThat(calls.getFirst().body())
        .contains("\"token\":\"tok-device-abc123\"", "\"title\":\"Payment complete\"", "\"body\":\"You sent Tk 50.00\"",
            "\"messageId\":\"m-1\"", "\"platform\":\"ANDROID\"");
  }

  @Test
  void aServerErrorIsRetriedWithExponentialBackoffUntilItGoesThrough() throws Exception {
    answers.add(new int[] {503, 0});
    answers.add(new int[] {500, 0});

    PushResult r = client.send(message());

    assertThat(r.delivered()).isTrue();
    assertThat(calls).hasSize(3);
    assertThat(Duration.ofNanos(calls.get(1).atNanos() - calls.get(0).atNanos())).isGreaterThanOrEqualTo(Duration.ofMillis(100));
    assertThat(Duration.ofNanos(calls.get(2).atNanos() - calls.get(1).atNanos())).isGreaterThanOrEqualTo(Duration.ofMillis(200));
  }

  @Test
  void aServerErrorOnEveryAttemptIsTransientAfterThreeCalls() {
    answers.add(new int[] {500, 0});
    answers.add(new int[] {500, 0});
    answers.add(new int[] {500, 0});

    assertThatThrownBy(() -> client.send(message()))
        .isInstanceOfSatisfying(PushSendException.class, e -> assertThat(e.httpStatus()).isEqualTo(500));
    assertThat(calls).hasSize(3);
  }

  @Test
  void aTimeoutIsRetriedToo() throws Exception {
    answers.add(new int[] {200, 600});

    PushResult r = client.send(message());

    assertThat(r.delivered()).isTrue();
    assertThat(calls).hasSize(2);
  }

  @Test
  void anUnregisteredTokenIsRefusedAtOnceWithoutRetry() {
    answers.add(new int[] {404, 0});

    assertThatThrownBy(() -> client.send(message()))
        .isInstanceOfSatisfying(PushRefusedException.class, e -> {
          assertThat(e.httpStatus()).isEqualTo(404);
          assertThat(e.unregistered()).isTrue();
        });
    assertThat(calls).hasSize(1);
  }
}
