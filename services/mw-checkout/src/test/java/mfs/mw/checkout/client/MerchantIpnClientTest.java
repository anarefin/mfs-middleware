package mfs.mw.checkout.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.net.InetAddress;
import javax.net.ssl.SSLContext;
import mfs.mw.checkout.config.IpnProperties;
import mfs.mw.checkout.security.IpnUrlPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * One IPN attempt against a local merchant (spec 9.3 step 3, ticket 32): what the merchant answered,
 * or which failure it was, always as a {@link MerchantIpnClient.Result}, never an exception.
 */
class MerchantIpnClientTest {

  private static final Duration LIMIT = Duration.ofMillis(500);
  static final IpnProperties PROPS =
      new IpnProperties(false, Duration.ofSeconds(1), List.of(Duration.ofMinutes(1)), 10, Duration.ofMillis(500),
          List.of("127.0.0.1"));

  /** A request the merchant got. */
  private record Seen(String path, String contentType, String signature, String timestamp, String body) {}

  private final List<Seen> seen = new CopyOnWriteArrayList<>();
  private final CountDownLatch release = new CountDownLatch(1);
  private HttpServer merchant;
  private MerchantIpnClient client;

  @BeforeEach
  void setUp() throws Exception {
    merchant = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    merchant.setExecutor(Executors.newCachedThreadPool());
    merchant.createContext("/ok", ex -> answer(ex, 200));
    merchant.createContext("/fail", ex -> answer(ex, 500));
    merchant.createContext("/redirect", ex -> {
      record(ex);
      ex.getResponseHeaders().add("Location", url("/ok").toString());
      ex.sendResponseHeaders(302, -1);
      ex.close();
    });
    merchant.createContext("/slow", ex -> {
      record(ex);
      try {
        release.await(5, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      ex.sendResponseHeaders(200, -1);
      ex.close();
    });
    merchant.start();
    TimeLimiterRegistry limiters =
        TimeLimiterRegistry.of(TimeLimiterConfig.custom().timeoutDuration(LIMIT).cancelRunningFuture(true).build());
    client = new MerchantIpnClient(limiters, PROPS, SSLContext.getDefault());
  }

  @AfterEach
  void tearDown() {
    release.countDown();
    merchant.stop(0);
  }

  private void record(HttpExchange ex) throws IOException {
    String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    seen.add(new Seen(ex.getRequestURI().getPath(), ex.getRequestHeaders().getFirst("Content-Type"),
        ex.getRequestHeaders().getFirst("X-Bits-Signature"), ex.getRequestHeaders().getFirst("X-Bits-Timestamp"),
        body));
  }

  private void answer(HttpExchange ex, int status) throws IOException {
    record(ex);
    ex.sendResponseHeaders(status, -1);
    ex.close();
  }

  private IpnUrlPolicy.Decision url(String path) {
    return pinned("http://127.0.0.1:" + merchant.getAddress().getPort() + path);
  }

  /** A checked target: the URL and the one address it may connect to (here: loopback). */
  static IpnUrlPolicy.Decision pinned(String url) {
    return new IpnUrlPolicy.Decision(null, URI.create(url), List.of(InetAddress.getLoopbackAddress()));
  }

  private static final Map<String, String> SIGNED =
      Map.of("X-Bits-Signature", "sig-fake-value", "X-Bits-Timestamp", "1700000000");

  @Test
  void a2xxAnswerIsDeliveredAndTheSignatureHeadersAndJsonBodyReachTheMerchant() {
    MerchantIpnClient.Result r = client.post(url("/ok"), SIGNED, "{\"orderId\":\"ORD-1\"}");

    assertThat(r.delivered()).isTrue();
    assertThat(r.httpStatus()).isEqualTo(200);
    assertThat(r.error()).isNull();
    assertThat(seen).containsExactly(
        new Seen("/ok", "application/json", "sig-fake-value", "1700000000", "{\"orderId\":\"ORD-1\"}"));
  }

  @Test
  void aServerErrorIsAnAnswerButNotADelivery() {
    MerchantIpnClient.Result r = client.post(url("/fail"), SIGNED, "{}");

    assertThat(r.delivered()).isFalse();
    assertThat(r.httpStatus()).isEqualTo(500);
    assertThat(r.error()).isNull();
  }

  @Test
  void aRedirectIsNotFollowedSoTheCallCannotBeBouncedElsewhere() {
    MerchantIpnClient.Result r = client.post(url("/redirect"), SIGNED, "{}");

    assertThat(r.httpStatus()).isEqualTo(302);
    assertThat(r.delivered()).isFalse();
    assertThat(seen).extracting(Seen::path).containsExactly("/redirect");
  }

  @Test
  void aMerchantSlowerThanTheTimeLimiterIsATimeoutWithinTheLimit() {
    MerchantIpnClient.Result r = client.post(url("/slow"), SIGNED, "{}");

    assertThat(r.error()).isEqualTo("TIMEOUT");
    assertThat(r.httpStatus()).isNull();
    assertThat(r.delivered()).isFalse();
    assertThat(r.durationMs()).isGreaterThanOrEqualTo(LIMIT.toMillis()).isLessThan(3000);
  }

  @Test
  void aClosedPortIsAConnectFailure() throws IOException {
    int port;
    try (ServerSocket s = new ServerSocket(0)) {
      port = s.getLocalPort();
    }

    MerchantIpnClient.Result r = client.post(pinned("http://127.0.0.1:" + port + "/ipn"), SIGNED, "{}");

    assertThat(r.error()).isEqualTo("CONNECT_FAILED");
    assertThat(r.httpStatus()).isNull();
  }

  @Test
  void aConnectionClosedWithoutAnAnswerIsAnIoError() throws Exception {
    try (ServerSocket dropper = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
      Thread acceptor = Thread.ofVirtual().start(() -> {
        try (Socket s = dropper.accept()) {
          s.getInputStream().read();
        } catch (IOException e) {
          // the test is over
        }
      });

      MerchantIpnClient.Result r =
          client.post(pinned("http://127.0.0.1:" + dropper.getLocalPort() + "/ipn"), SIGNED, "{}");

      assertThat(r.error()).isEqualTo("IO_ERROR");
      assertThat(r.httpStatus()).isNull();
      acceptor.join(2000);
    }
  }

  @Test
  void anInterruptedCallerGetsInterruptedAndKeepsItsInterruptFlag() {
    Thread.currentThread().interrupt();
    MerchantIpnClient.Result r;
    try {
      r = client.post(url("/slow"), SIGNED, "{}");
    } finally {
      assertThat(Thread.interrupted()).isTrue();
    }

    assertThat(r.error()).isEqualTo("INTERRUPTED");
    assertThat(r.httpStatus()).isNull();
  }

  @Test
  void onlyA2xxStatusCountsAsDelivered() {
    assertThat(new MerchantIpnClient.Result(199, null, 1).delivered()).isFalse();
    assertThat(new MerchantIpnClient.Result(204, null, 1).delivered()).isTrue();
    assertThat(new MerchantIpnClient.Result(300, null, 1).delivered()).isFalse();
    assertThat(new MerchantIpnClient.Result(null, "TIMEOUT", 1).delivered()).isFalse();
  }

  /**
   * The name is never looked up again: the call goes to the checked address only, while the Host
   * header keeps the merchant's name (review A7). The name here resolves nowhere.
   */
  @Test
  void theCallGoesToTheCheckedAddressWithTheMerchantsHostHeader() {
    java.util.concurrent.atomic.AtomicReference<String> host = new java.util.concurrent.atomic.AtomicReference<>();
    merchant.createContext("/host", ex -> {
      host.set(ex.getRequestHeaders().getFirst("Host"));
      answer(ex, 204);
    });
    int port = merchant.getAddress().getPort();

    MerchantIpnClient.Result r = client.post(pinned("http://merchant.invalid:" + port + "/host"), SIGNED, "{}");

    assertThat(r.httpStatus()).isEqualTo(204);
    assertThat(host.get()).isEqualTo("merchant.invalid:" + port);
  }
}
