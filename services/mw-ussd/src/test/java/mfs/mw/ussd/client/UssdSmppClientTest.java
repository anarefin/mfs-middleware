package mfs.mw.ussd.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static mfs.mw.ussd.Await.await;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import mfs.mw.ussd.config.UssdSmppProperties;
import mfs.mw.ussd.dto.SmppUssdMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The SMPP USSD binds against an in-test MNO gateway (spec 9.2.1): transceiver bind, enquire_link,
 * reconnect after a dropped bind or three missed enquire_links, delayed retry on generic_nack /
 * 0x58 / 0x14, TPS throttle, the {@code mw_smpp_bind_up} gauge and {@code smpp.bind.down} event.
 */
class UssdSmppClientTest {

  static final String MSISDN = "8801712345678";

  private UssdGatewayStub gateway;
  private UssdSmppClient client;
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final List<SmppUssdMessage> inbound = new CopyOnWriteArrayList<>();
  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

  @BeforeEach
  void setUp() throws Exception {
    gateway = new UssdGatewayStub();
    logs.start();
    ((Logger) LoggerFactory.getLogger(UssdSmppBind.class)).addAppender(logs);
  }

  @AfterEach
  void tearDown() throws Exception {
    ((Logger) LoggerFactory.getLogger(UssdSmppBind.class)).detachAppender(logs);
    if (client != null) {
      client.stop();
    }
    gateway.close();
  }

  UssdSmppProperties.Endpoint endpoint(int binds, Duration enquireLink, int tps) {
    return new UssdSmppProperties.Endpoint("127.0.0.1", gateway.port(), "upay", "fake-pw", null, null, binds,
        enquireLink, 3, Duration.ofMillis(300), Duration.ofMillis(100), Duration.ofMillis(400), 4, tps,
        List.of(Duration.ofMillis(50), Duration.ofMillis(50), Duration.ofMillis(50)));
  }

  /** Starts a client for gp whose listener answers every deliver_sm with "Hello" on the same bind. */
  void start(UssdSmppProperties.Endpoint ep) {
    client = new UssdSmppClient(new UssdSmppProperties(true, Map.of("gp", ep, "robi", ep)), java.util.Set.of("gp"),
        meters, Clock.systemUTC());
    client.listen(m -> {
      inbound.add(m);
      Thread.ofVirtual().start(() -> {
        try {
          client.submit(new SmppUssdMessage(m.mno(), m.bind(), m.address(), m.serviceCode(),
              SmppUssdMessage.USSR_REQUEST, m.sessionNumber(), m.sequence() + 1, false, "Hello"));
        } catch (UssdSmppException e) {
          // the test sees no screen
        }
      });
    });
    client.start();
    await().atMost(Duration.ofSeconds(10)).until(() -> gateway.boundSessions() == ep.binds());
  }

  double bindUp(String bind) {
    return meters.get("mw.smpp.bind.up").tag("mno", "gp").tag("bind", bind).gauge().value();
  }

  @Test
  void bindsAsTransceiverAndAnswersOnTheBindThatReceivedTheSession() {
    start(endpoint(2, Duration.ofSeconds(30), 50));

    List<UssdGatewayStub.Screen> screens = gateway.dial(1, MSISDN, "*268#", List.of());

    assertThat(inbound).singleElement().satisfies(m -> {
      assertThat(m.mno()).isEqualTo("gp");
      assertThat(m.address()).isEqualTo(MSISDN);
      assertThat(m.serviceCode()).isEqualTo("*268#");
      assertThat(m.serviceOp()).isEqualTo(SmppUssdMessage.PSSR_INDICATION);
      assertThat(m.endOfSession()).isFalse();
      assertThat(m.text()).isEqualTo("*268#");
    });
    assertThat(screens).singleElement().satisfies(s -> {
      assertThat(s.bindIndex()).isEqualTo(1);
      assertThat(s.address()).isEqualTo(MSISDN);
      assertThat(s.serviceCode()).isEqualTo("*268#");
      assertThat(s.serviceType()).isEqualTo("USSD");
      assertThat(s.serviceOp()).isEqualTo(SmppUssdMessage.USSR_REQUEST);
      assertThat(s.sessionNumber()).isEqualTo(inbound.getFirst().sessionNumber());
      assertThat(s.text()).isEqualTo("Hello");
    });
    assertThat(bindUp("gp-1")).isEqualTo(1.0);
    assertThat(bindUp("gp-2")).isEqualTo(1.0);
  }

  @Test
  void sendsEnquireLinkOnTheInterval() {
    start(endpoint(1, Duration.ofMillis(200), 50));

    await().atMost(Duration.ofSeconds(5)).until(() -> gateway.enquireLinkCount() >= 3);
  }

  @Test
  void aDroppedBindIsReportedAndReestablished() {
    start(endpoint(1, Duration.ofSeconds(30), 50));

    gateway.dropBinds();

    await().atMost(Duration.ofSeconds(5)).until(() -> events("smpp.bind.down") >= 1);
    await().atMost(Duration.ofSeconds(10)).until(() -> gateway.bindCount() == 2 && gateway.boundSessions() == 1);
    await().atMost(Duration.ofSeconds(5)).until(() -> bindUp("gp-1") == 1.0);
    assertThat(gateway.dial(0, MSISDN, "*268#", List.of())).singleElement()
        .satisfies(s -> assertThat(s.text()).isEqualTo("Hello"));
  }

  @Test
  void theGaugeDropsToZeroWhileTheBindIsDown() throws Exception {
    start(endpoint(1, Duration.ofSeconds(30), 50));
    assertThat(bindUp("gp-1")).isEqualTo(1.0);

    int port = gateway.port();
    gateway.close();

    await().atMost(Duration.ofSeconds(5)).until(() -> bindUp("gp-1") == 0.0);
    assertThat(port).isPositive();
    gateway = new UssdGatewayStub();
  }

  @Test
  void threeMissedEnquireLinksDropTheBindAndReconnect() {
    start(endpoint(1, Duration.ofMillis(100), 50));
    gateway.silent(true);

    await().atMost(Duration.ofSeconds(10)).until(() -> gateway.bindCount() >= 2);
    assertThat(logs.list).anySatisfy(e -> {
      assertThat(kv(e, "event")).isEqualTo("smpp.bind.down");
      assertThat(kv(e, "reason")).isEqualTo("enquire_link");
    });
    gateway.silent(false);
    await().atMost(Duration.ofSeconds(10)).until(() -> gateway.boundSessions() == 1);
  }

  @Test
  void genericNackThrottlingAndQueueFullAreRetriedAfterADelay() {
    start(endpoint(1, Duration.ofSeconds(30), 50));
    gateway.genericNackNext(1);
    gateway.throttleNext(1);
    gateway.queueFullNext(1);

    List<UssdGatewayStub.Screen> screens = gateway.dial(0, MSISDN, "*268#", List.of());

    assertThat(gateway.refusedCount()).isEqualTo(3);
    assertThat(screens).singleElement().satisfies(s -> assertThat(s).isNotNull());
    assertThat(screens.getFirst().text()).isEqualTo("Hello");
  }

  @Test
  void givesUpWhenTheGatewayKeepsThrottling() {
    start(endpoint(1, Duration.ofSeconds(30), 50));
    gateway.throttleNext(100);

    assertThatThrownBy(() -> client.submit(new SmppUssdMessage("gp", "gp-1", MSISDN, "*268#",
            SmppUssdMessage.PSSR_RESPONSE, 1, 1, true, "Bye")))
        .isInstanceOf(UssdSmppException.class);
    assertThat(gateway.refusedCount()).isEqualTo(4);
  }

  @Test
  void submitsAreThrottledToTheBindsTps() throws Exception {
    start(endpoint(1, Duration.ofSeconds(30), 5));
    long started = System.nanoTime();

    for (int i = 0; i < 6; i++) {
      client.submit(new SmppUssdMessage("gp", "gp-1", MSISDN, "*268#", SmppUssdMessage.USSR_REQUEST, i, 1, false,
          "screen " + i));
    }

    assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(Duration.ofMillis(900));
    assertThat(gateway.screens()).hasSize(6);
  }

  @Test
  void anUnknownBindIsRefused() {
    start(endpoint(1, Duration.ofSeconds(30), 50));

    assertThatThrownBy(() -> client.submit(new SmppUssdMessage("gp", "gp-9", MSISDN, "*268#",
            SmppUssdMessage.USSR_REQUEST, 1, 1, false, "x")))
        .isInstanceOf(UssdSmppException.class);
  }

  private long events(String name) {
    return logs.list.stream().filter(e -> name.equals(kv(e, "event"))).count();
  }

  private static Object kv(ILoggingEvent e, String key) {
    if (e.getKeyValuePairs() == null) {
      return null;
    }
    return e.getKeyValuePairs().stream().filter(p -> p.key.equals(key)).map(p -> p.value).findFirst().orElse(null);
  }
}
