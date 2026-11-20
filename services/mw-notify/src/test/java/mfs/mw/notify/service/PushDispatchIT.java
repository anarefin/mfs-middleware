package mfs.mw.notify.service;

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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import mfs.mw.notify.MutableClock;
import mfs.mw.notify.TestInfrastructure;
import mfs.mw.notify.client.FakeSmsSender;
import mfs.mw.notify.dto.Channel;
import mfs.mw.notify.dto.NotificationRequest;
import mfs.mw.notify.dto.Priority;
import mfs.mw.notify.dto.PushTokenEvent;
import mfs.mw.notify.dto.TxnStatusEvent;
import mfs.mw.notify.dto.TxnStatusEvent.Amount;
import mfs.mw.notify.dto.TxnStatusEvent.Party;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The push channel (ticket 110, FRS EXT-04, NOT-01/02/06, FL-14) at its seams: a registered device token, a
 * {@code mw.txn.status.v1} record through the rules, the push dispatcher, and a stand-in push server that
 * stores every push it accepts (the dev stack's mw-simulators {@code push} mock plays it there). What leaves
 * the service is observed in the stand-in's store, the SMSC seam ({@link FakeSmsSender}) and the {@code
 * mw.notify.status.v1} events in the outbox.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "mw.notify.consumers.enabled=false",
      "mw.notify.smpp.enabled=false",
      "mw.notify.dispatch.enabled=false",
      "mw.notify.push.enabled=true",
      "mw.notify.push.api-key=" + PushDispatchIT.KEY,
      "mw.notify.push.timeout=500ms",
      "resilience4j.retry.instances.push.wait-duration=10ms",
      "resilience4j.retry.instances.push.enable-randomized-wait=false"
    })
@Import({TestInfrastructure.class, PushDispatchIT.Fixtures.class})
class PushDispatchIT {

  static final String KEY = "fake-push-key-for-tests";
  static final Instant T0 = Instant.parse("2026-11-04T08:00:00Z");
  static final String WALLET = "01711000001";

  record Stored(String authorization, JsonNode body) {}

  static final List<Stored> STORED = new CopyOnWriteArrayList<>();
  static final AtomicInteger CALLS = new AtomicInteger();
  /** 200 = accept and store; else answer that status and store nothing. */
  static final AtomicInteger ANSWER = new AtomicInteger(200);
  static final HttpServer PUSH_SERVER = pushServer();

  private static HttpServer pushServer() {
    try {
      JsonMapper json = JsonMapper.builder().build();
      HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      s.createContext("/push/v1/messages", ex -> {
        CALLS.incrementAndGet();
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        int status = ANSWER.get();
        byte[] answer;
        if (status == 200) {
          STORED.add(new Stored(ex.getRequestHeaders().getFirst("Authorization"), json.readTree(body)));
          answer = ("{\"messageId\":\"push-" + STORED.size() + "\",\"status\":\"DELIVERED\"}").getBytes(StandardCharsets.UTF_8);
        } else {
          answer = "{\"error\":\"INTERNAL\"}".getBytes(StandardCharsets.UTF_8);
        }
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, answer.length);
        ex.getResponseBody().write(answer);
        ex.close();
      });
      s.start();
      return s;
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @DynamicPropertySource
  static void push(DynamicPropertyRegistry r) {
    r.add("mw.notify.push.base-url", () -> "http://127.0.0.1:" + PUSH_SERVER.getAddress().getPort());
  }

  @AfterAll
  static void stop() {
    PUSH_SERVER.stop(0);
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class Fixtures {
    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock(T0);
    }

    @Bean
    @Primary
    FakeSmsSender fakeSmsSender() {
      return new FakeSmsSender();
    }
  }

  private final TxnStatusService txnStatuses;
  private final NotificationService notifications;
  private final PushDeviceService devices;
  private final PushDispatchService pushDispatcher;
  private final DispatchService dispatcher;
  private final FakeSmsSender smsc;
  private final MutableClock clock;
  private final JdbcTemplate jdbc;
  private final JsonMapper json = JsonMapper.builder().build();

  @Autowired
  PushDispatchIT(TxnStatusService txnStatuses, NotificationService notifications, PushDeviceService devices,
      PushDispatchService pushDispatcher, DispatchService dispatcher, FakeSmsSender smsc, MutableClock clock,
      JdbcTemplate jdbc) {
    this.txnStatuses = txnStatuses;
    this.notifications = notifications;
    this.devices = devices;
    this.pushDispatcher = pushDispatcher;
    this.dispatcher = dispatcher;
    this.smsc = smsc;
    this.clock = clock;
    this.jdbc = jdbc;
  }

  @BeforeEach
  void reset() {
    STORED.clear();
    CALLS.set(0);
    ANSWER.set(200);
    smsc.reset();
    clock.set(T0);
    jdbc.update("update message set status = 'FAILED' where status in ('QUEUED', 'RETRY')");
    jdbc.update("delete from push_device");
    jdbc.update("delete from push_device_binding");
  }

  private void register(String wallet, String device, String token, Instant at) {
    devices.register(new PushTokenEvent(1, wallet, device, token, PushTokenEvent.Platform.ANDROID, at, "corr-reg"));
  }

  private static String txnId() {
    return UUID.randomUUID().toString();
  }

  private static TxnStatusEvent p2p(String txnId, String status, String locale) {
    return new TxnStatusEvent(txnId, "corr-" + txnId, "CUSTOMER_APP", "P2P", status,
        new Party("MSISDN", WALLET, null), new Party("WALLET", WALLET, "CUSTOMER"),
        new Party("WALLET", "01811000002", "CUSTOMER"), new Amount(5000, "BDT"), new Amount(0, "BDT"), "CORE-1",
        null, locale, null, null);
  }

  private List<String> statusNames(String messageId) {
    return jdbc.queryForList(
            "select payload::text from outbox where topic = 'mw.notify.status.v1' and msg_key = ? order by id",
            String.class, messageId)
        .stream()
        .map(json::readTree)
        .filter(n -> "PUSH".equals(n.get("channel").asString()))
        .map(n -> n.get("status").asString() + (n.get("final").asBoolean() ? "!" : "")
            + (n.hasNonNull("errorCode") ? " " + n.get("errorCode").asString() : ""))
        .toList();
  }

  @Test
  void aCompletedTxnPushesTheRenderedTextToTheRegisteredTokenAndTheSmsGoesToo() {
    register(WALLET, "dev-1", "tok-device-aaa111", T0.minusSeconds(60));
    String txn = txnId();

    txnStatuses.onStatus(p2p(txn, "COMPLETED", "en"));
    pushDispatcher.dispatchDue();
    dispatcher.dispatchDue();

    assertThat(STORED).singleElement().satisfies(s -> {
      assertThat(s.authorization()).isEqualTo("key=" + KEY);
      assertThat(s.body().path("token").asString()).isEqualTo("tok-device-aaa111");
      assertThat(s.body().path("platform").asString()).isEqualTo("ANDROID");
      assertThat(s.body().path("notification").path("title").asString()).isEqualTo("Transaction complete");
      assertThat(s.body().path("notification").path("body").asString())
          .isEqualTo("Your P2P of Tk 50.00 is complete. TrxID " + txn);
      assertThat(s.body().path("data").path("txnId").asString()).isEqualTo(txn);
    });
    assertThat(statusNames(txn + ":push:push-txn-completed")).containsExactly("DELIVERED!");
    assertThat(smsc.sent()).extracting(x -> x.part().destination()).contains(WALLET);
  }

  @Test
  void aFailedTxnPushIsInBanglaWhenTheTxnIsBangla() {
    register(WALLET, "dev-1", "tok-device-bbb222", T0.minusSeconds(60));
    String txn = txnId();

    txnStatuses.onStatus(p2p(txn, "FAILED", "bn"));
    pushDispatcher.dispatchDue();

    assertThat(STORED).singleElement().satisfies(s -> {
      assertThat(s.body().path("notification").path("title").asString()).isEqualTo("লেনদেন ব্যর্থ");
      assertThat(s.body().path("notification").path("body").asString())
          .isEqualTo("আপনার ৳50.00 এর P2P ব্যর্থ হয়েছে। TrxID " + txn);
    });
  }

  @Test
  void aWalletWithoutATokenGetsNoPushButStillItsSms() {
    String txn = txnId();

    txnStatuses.onStatus(p2p(txn, "COMPLETED", "en"));
    pushDispatcher.dispatchDue();
    dispatcher.dispatchDue();

    assertThat(CALLS).hasValue(0);
    assertThat(jdbc.queryForObject("select count(*) from message where txn_id = ? and channel = 'PUSH'", Integer.class, txn))
        .isZero();
    assertThat(smsc.sent()).extracting(x -> x.part().destination()).contains(WALLET);
  }

  @Test
  void aPushServer5xxIsRetriedThenFailedAndTheSmsForTheSameTxnIsStillSent() {
    register(WALLET, "dev-1", "tok-device-ccc333", T0.minusSeconds(60));
    ANSWER.set(503);
    String txn = txnId();
    String push = txn + ":push:push-txn-completed";

    txnStatuses.onStatus(p2p(txn, "COMPLETED", "en"));
    pushDispatcher.dispatchDue();
    dispatcher.dispatchDue();
    assertThat(smsc.sent()).extracting(x -> x.part().destination()).contains(WALLET);
    for (Duration wait : List.of(Duration.ofSeconds(2), Duration.ofSeconds(10), Duration.ofSeconds(30))) {
      clock.advance(wait);
      pushDispatcher.dispatchDue();
    }

    // 4 dispatch attempts, each retried 3 times by the client with backoff
    assertThat(CALLS).hasValue(12);
    assertThat(STORED).isEmpty();
    assertThat(statusNames(push)).containsExactly(
        "FAILED MW-SYS-104", "FAILED MW-SYS-104", "FAILED MW-SYS-104", "FAILED! MW-SYS-102");
    assertThat(jdbc.queryForObject("select status from message where message_id = ? and channel = 'PUSH'", String.class, push))
        .isEqualTo("FAILED");
    assertThat(jdbc.queryForObject("select status from message where message_id = ? and channel = 'SMS'", String.class,
            txn + ":sms:p2p-sent"))
        .isEqualTo("SENT");
  }

  @Test
  void aRebindRemovesTheOldTokenAndNoPushGoesToItAfterwards() {
    register(WALLET, "dev-old", "tok-device-old444", T0.minusSeconds(60));
    String queuedBefore = txnId();
    txnStatuses.onStatus(p2p(queuedBefore, "COMPLETED", "en"));

    // mw-auth's "a new device was bound" notice names the new device
    notifications.accept(new NotificationRequest(1, "device-bound:" + UUID.randomUUID(), null, "corr-bind",
        new NotificationRequest.Recipient(WALLET, null, null, null), "DEVICE_BOUND", "en",
        Map.of("deviceId", "dev-new"), List.of(Channel.SMS), Priority.TXN, null, null));
    String after = txnId();
    txnStatuses.onStatus(p2p(after, "COMPLETED", "en"));
    pushDispatcher.dispatchDue();

    assertThat(devices.tokenOf(WALLET)).isEmpty();
    assertThat(CALLS).hasValue(0);
    assertThat(statusNames(queuedBefore + ":push:push-txn-completed")).containsExactly("FAILED! MW-VAL-103");
    assertThat(jdbc.queryForObject("select count(*) from message where txn_id = ? and channel = 'PUSH'", Integer.class, after))
        .isZero();

    register(WALLET, "dev-new", "tok-device-new555", T0);
    String next = txnId();
    txnStatuses.onStatus(p2p(next, "COMPLETED", "en"));
    pushDispatcher.dispatchDue();
    assertThat(STORED).singleElement().satisfies(s -> assertThat(s.body().path("token").asString()).isEqualTo("tok-device-new555"));
  }

  private void deviceBound(String wallet, String device) {
    notifications.accept(new NotificationRequest(1, "device-bound:" + UUID.randomUUID(), null, "corr-bind",
        new NotificationRequest.Recipient(wallet, null, null, null), "DEVICE_BOUND", "en",
        Map.of("deviceId", device), List.of(Channel.SMS), Priority.TXN, null, null));
  }

  @Test
  void aDeviceThatMovesToAnotherWalletLeavesNoRowUnderTheOldWalletAndGetsNoneOfItsPushes() {
    String walletB = "01711000077";
    register(WALLET, "dev-moving", "tok-device-mov111", T0.minusSeconds(60));

    deviceBound(walletB, "dev-moving");
    String txn = txnId();
    txnStatuses.onStatus(p2p(txn, "COMPLETED", "en"));
    pushDispatcher.dispatchDue();

    assertThat(jdbc.queryForObject("select count(*) from push_device where wallet_id = ? and device_id = 'dev-moving'",
        Integer.class, WALLET)).isZero();
    assertThat(devices.tokenOf(WALLET)).isEmpty();
    assertThat(CALLS).hasValue(0);
  }

  @Test
  void aRegistrationStampedBeforeARevocationIsIgnoredWhenItArrivesLater() {
    register(WALLET, "dev-old", "tok-device-old222", T0.minusSeconds(60));
    deviceBound(WALLET, "dev-new"); // revokes dev-old at T0

    // the old device's registration of T0 - 10 s comes in after the revocation (another topic)
    register(WALLET, "dev-old", "tok-device-late33", T0.minusSeconds(10));
    // and the moved device's old registration under another wallet is refused too
    deviceBound("01711000078", "dev-moved");
    register(WALLET, "dev-moved", "tok-device-late44", T0.minusSeconds(5));

    assertThat(devices.tokenOf(WALLET)).isEmpty();
    // the new device registers after its binding: kept
    register(WALLET, "dev-new", "tok-device-new666", T0.plusSeconds(1));
    assertThat(devices.tokenOf(WALLET)).contains("tok-device-new666");
  }

  @Test
  void aNewDeviceRegistrationReplacesTheWalletsOldDeviceAndAnOlderOneIsIgnored() {
    register(WALLET, "dev-1", "tok-device-ddd666", T0.minusSeconds(60));
    register(WALLET, "dev-2", "tok-device-eee777", T0);
    register(WALLET, "dev-2", "tok-device-stale8", T0.minusSeconds(30));

    assertThat(devices.tokenOf(WALLET)).contains("tok-device-eee777");
    assertThat(jdbc.queryForObject("select count(*) from push_device where wallet_id = ?", Integer.class, WALLET)).isEqualTo(1);
  }

  @Test
  void aTokenThePushServerNoLongerKnowsIsDroppedAndTheMessageFailed() {
    register(WALLET, "dev-1", "tok-device-fff999", T0.minusSeconds(60));
    ANSWER.set(404);
    String txn = txnId();

    txnStatuses.onStatus(p2p(txn, "COMPLETED", "en"));
    pushDispatcher.dispatchDue();

    assertThat(CALLS).hasValue(1);
    assertThat(statusNames(txn + ":push:push-txn-completed")).containsExactly("FAILED! MW-VAL-103");
    assertThat(devices.tokenOf(WALLET)).isEmpty();
  }
}
