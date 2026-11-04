package mfs.mw.checkout;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import mfs.mw.checkout.dto.Amount;
import mfs.mw.checkout.dto.ErrorInfo;
import mfs.mw.checkout.dto.SessionStatus;
import mfs.mw.checkout.dto.StatusMessage;
import mfs.mw.checkout.entity.CheckoutSession;
import mfs.mw.checkout.entity.IpnDelivery;
import mfs.mw.checkout.repository.IpnDeliveryRepository;
import mfs.mw.checkout.repository.SessionRepository;
import mfs.mw.checkout.security.IpnSigner;
import mfs.mw.checkout.service.IpnDeliveryService;
import mfs.mw.checkout.service.SessionOutcomes;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The signed IPN with retries (spec 9.3 step 3, ticket 32) on real PostgreSQL: a finished session
 * gets an {@code ipn_delivery} row in the outcome's transaction; {@link
 * IpnDeliveryService#deliverDue()} (what the ShedLock-guarded job runs) posts it to a local merchant
 * server, retrying 1 min, 5 min, 30 min, 2 h and 6 h after each failure (a {@link MutableClock}
 * stands in for the wait) until a 2xx; every attempt is a row.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "mw.checkout.ipn.dev-hosts=127.0.0.1",
      "resilience4j.timelimiter.instances.merchant-ipn.timeout-duration=1s"
    })
@Import({TestInfrastructure.class, IpnDeliveryIT.Fixtures.class})
@DirtiesContext
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IpnDeliveryIT {

  static final JsonMapper JSON = JsonMapper.builder().build();
  static final String SECRET = "test-merchant-secret-not-real";

  @TestConfiguration(proxyBeanMethods = false)
  static class Fixtures {
    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock(Instant.now());
    }
  }

  record Received(String path, Map<String, String> headers, String body) {}

  private final SessionRepository sessions;
  private final SessionOutcomes outcomes;
  private final IpnDeliveryService ipn;
  private final IpnDeliveryRepository deliveries;
  private final MutableClock clock;

  private HttpServer merchant;
  private final ConcurrentLinkedQueue<Integer> answers = new ConcurrentLinkedQueue<>();
  private final List<Received> received = new CopyOnWriteArrayList<>();
  private volatile long delayMs;

  @Autowired
  IpnDeliveryIT(
      SessionRepository sessions, SessionOutcomes outcomes, IpnDeliveryService ipn,
      IpnDeliveryRepository deliveries, MutableClock clock) {
    this.sessions = sessions;
    this.outcomes = outcomes;
    this.ipn = ipn;
    this.deliveries = deliveries;
    this.clock = clock;
  }

  @BeforeAll
  void startMerchant() throws IOException {
    merchant = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    merchant.createContext("/", ex -> {
      byte[] body = ex.getRequestBody().readAllBytes();
      Map<String, String> h = new java.util.HashMap<>();
      ex.getRequestHeaders().forEach((k, v) -> h.put(k.toLowerCase(java.util.Locale.ROOT), v.getFirst()));
      received.add(new Received(ex.getRequestURI().toString(), h, new String(body, StandardCharsets.UTF_8)));
      try {
        Thread.sleep(delayMs);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      Integer status = answers.poll();
      ex.sendResponseHeaders(status == null ? 200 : status, -1);
      ex.close();
    });
    merchant.start();
  }

  @AfterAll
  void stopMerchant() {
    merchant.stop(0);
  }

  @BeforeEach
  void reset() {
    answers.clear();
    received.clear();
    delayMs = 0;
  }

  String ipnUrl(String orderId) {
    return "http://127.0.0.1:" + merchant.getAddress().getPort() + "/ipn/" + orderId;
  }

  @Test
  void theMerchantFailsTwiceThenAcceptsAndGetsOneSignedIpnPerAttemptOnTheSchedule() throws Exception {
    CheckoutSession s = paying(ipnUrl("ok"));
    answers.addAll(List.of(500, 503, 200));

    finish(s, "COMPLETED", null);
    List<IpnDelivery> rows = deliveries.findBySession(s.sessionId());
    assertThat(rows).as("written with the outcome, nothing sent yet").singleElement()
        .satisfies(r -> assertThat(r.status()).isEqualTo(IpnDelivery.Status.PENDING));
    assertThat(received).isEmpty();

    ipn.deliverDue();
    assertThat(mine(s)).hasSize(1);
    ipn.deliverDue();
    clock.advance(Duration.ofSeconds(59));
    ipn.deliverDue();
    assertThat(mine(s)).as("the first retry waits a minute").hasSize(1);

    clock.advance(Duration.ofSeconds(1));
    ipn.deliverDue();
    assertThat(mine(s)).hasSize(2);

    clock.advance(Duration.ofMinutes(4));
    ipn.deliverDue();
    assertThat(mine(s)).as("the second retry waits five minutes").hasSize(2);
    clock.advance(Duration.ofMinutes(1));
    ipn.deliverDue();
    assertThat(mine(s)).hasSize(3);

    clock.advance(Duration.ofHours(7));
    ipn.deliverDue();
    assertThat(mine(s)).as("delivered: no more attempts").hasSize(3);

    List<Received> got = mine(s);
    for (int i = 0; i < got.size(); i++) {
      Received r = got.get(i);
      assertThat(r.headers()).containsEntry("content-type", "application/json")
          .containsEntry("x-upay-attempt", Integer.toString(i + 1))
          .containsEntry("x-upay-ipn-id", s.sessionId().toString());
      assertThat(IpnSigner.verify(SECRET, r.headers().get("x-upay-timestamp"), r.body(), r.headers().get("x-upay-signature")))
          .as("attempt %d is signed", i + 1).isTrue();
      assertThat(r.body()).as("every attempt sends the same body").isEqualTo(got.getFirst().body());
    }
    JsonNode body = JSON.readTree(got.getFirst().body());
    assertThat(body.path("sessionId").asString()).isEqualTo(s.sessionId().toString());
    assertThat(body.path("orderId").asString()).isEqualTo(s.orderId());
    assertThat(body.path("status").asString()).isEqualTo("PAID");
    assertThat(body.path("txnId").asString()).isEqualTo(s.txnId().toString());
    assertThat(body.path("amount").path("valueMinor").asLong()).isEqualTo(1000);
    assertThat(body.path("amount").path("currency").asString()).isEqualTo("BDT");
    assertThat(body.has("errorCode")).isFalse();
    assertThat(body.toString()).as("no payer data").doesNotContain("01711000001");

    rows = deliveries.findBySession(s.sessionId());
    assertThat(rows).extracting(IpnDelivery::attempt).containsExactly(1, 2, 3);
    assertThat(rows).extracting(IpnDelivery::status)
        .containsExactly(IpnDelivery.Status.FAILED, IpnDelivery.Status.FAILED, IpnDelivery.Status.DELIVERED);
    assertThat(rows).extracting(IpnDelivery::httpStatus).containsExactly(500, 503, 200);
    assertThat(rows).allSatisfy(r -> assertThat(r.attemptedAt()).isNotNull());
    assertThat(rows.get(1).dueAt()).isEqualTo(rows.get(0).attemptedAt().plus(Duration.ofMinutes(1)));
    assertThat(rows.get(2).dueAt()).isEqualTo(rows.get(1).attemptedAt().plus(Duration.ofMinutes(5)));
  }

  @Test
  void aMerchantThatNeverAcceptsGetsSixAttemptsThenNoMore() {
    CheckoutSession s = paying(ipnUrl("down"));
    for (int i = 0; i < 10; i++) {
      answers.add(500);
    }
    finish(s, "FAILED", "MW-CORE-INSUFFICIENT_BALANCE");

    for (Duration step : List.of(Duration.ZERO, Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30),
        Duration.ofHours(2), Duration.ofHours(6), Duration.ofHours(24))) {
      clock.advance(step);
      ipn.deliverDue();
    }

    assertThat(mine(s)).hasSize(6);
    assertThat(deliveries.findBySession(s.sessionId())).hasSize(6)
        .allSatisfy(r -> assertThat(r.status()).isEqualTo(IpnDelivery.Status.FAILED));
    JsonNode body = JSON.readTree(mine(s).getFirst().body());
    assertThat(body.path("status").asString()).isEqualTo("FAILED");
    assertThat(body.path("errorCode").asString()).isEqualTo("MW-CORE-INSUFFICIENT_BALANCE");
  }

  @Test
  void aSlowMerchantTimesOutAndIsRetried() {
    CheckoutSession s = paying(ipnUrl("slow"));
    delayMs = 2_000;
    finish(s, "COMPLETED", null);

    ipn.deliverDue();

    IpnDelivery first = deliveries.findBySession(s.sessionId()).getFirst();
    assertThat(first.status()).isEqualTo(IpnDelivery.Status.FAILED);
    assertThat(first.httpStatus()).isNull();
    assertThat(first.error()).isEqualTo("TIMEOUT");
    assertThat(deliveries.findBySession(s.sessionId())).hasSize(2);
  }

  @Test
  void anInternalTargetIsNeverCalledAndTheRefusalIsRecorded() {
    CheckoutSession s = paying("https://169.254.169.254/latest/meta-data");
    finish(s, "COMPLETED", null);

    ipn.deliverDue();

    IpnDelivery first = deliveries.findBySession(s.sessionId()).getFirst();
    assertThat(first.status()).isEqualTo(IpnDelivery.Status.FAILED);
    assertThat(first.error()).startsWith("URL_REFUSED");
    assertThat(first.httpStatus()).isNull();
  }

  // ---- helpers ------------------------------------------------------------------------------

  List<Received> mine(CheckoutSession s) {
    return received.stream().filter(r -> s.sessionId().toString().equals(r.headers().get("x-upay-ipn-id"))).toList();
  }

  /** A session the customer is paying (OTP checked, MERCHANT_PAY sent). */
  CheckoutSession paying(String ipnUrl) {
    Instant now = clock.instant();
    CheckoutSession s =
        new CheckoutSession(
            UUID.randomUUID(), "tok-" + UUID.randomUUID(), "merchant_demo", "01911000001", "ORD-" + UUID.randomUUID(),
            1000, "BDT", null, "en", "https://s.example.test/ok", "https://s.example.test/fail",
            "https://s.example.test/cancel", ipnUrl, SessionStatus.OPEN, null, null, null, 0, 0, null, null, now,
            now.plusSeconds(900), now);
    sessions.insert(s);
    sessions.otpIssued(s.sessionId(), "01711000001", "h", now.plusSeconds(120), 3, now);
    UUID txnId = UUID.randomUUID();
    assertThat(sessions.claimForPayment(s.sessionId(), "h", txnId, 3, now)).isTrue();
    return sessions.findById(s.sessionId()).orElseThrow();
  }

  void finish(CheckoutSession s, String status, String errorCode) {
    outcomes.apply(
        new StatusMessage(
            1, s.txnId().toString(), "checkout:" + s.sessionId(), null, "WEB_CHECKOUT", "MERCHANT_PAY", status,
            "PROCESSING", null, null, null, new Amount(1000L, "BDT"), null, null,
            errorCode == null ? null : new ErrorInfo(errorCode, "x", null, false), "en", null, clock.instant(), clock.instant()));
  }
}
