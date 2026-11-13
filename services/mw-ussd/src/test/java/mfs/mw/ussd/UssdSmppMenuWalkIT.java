package mfs.mw.ussd;

import static mfs.mw.ussd.Await.await;
import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import mfs.mw.ussd.client.UssdGatewayStub;
import mfs.mw.ussd.dto.SmppUssdMessage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * The menu-walk suite over the SMPP transport: the same robi menu, with only configuration
 * changed ({@code mw.ussd.mno.robi.transport=SMPP} and robi's SMPP endpoint, here an in-test MNO
 * gateway with two binds). Plus what only SMPP has: bind affinity, the final PSSR response,
 * subscriber release, and a throttled screen retried within the session.
 */
@SpringBootTest(properties = {"mw.ussd.sync-budget=5s", "mw.ussd.instance-id=it-ussd-walk-smpp",
    "mw.ussd.consumers.enabled=true", "mw.ussd.mno.robi.transport=SMPP", "mw.ussd.smpp.enabled=true",
    "mw.ussd.smpp.mno.robi.host=127.0.0.1", "mw.ussd.smpp.mno.robi.binds=2",
    "mw.ussd.smpp.mno.robi.retry-delays=100ms,100ms,100ms"})
@AutoConfigureMockMvc
@Import(TestInfrastructure.class)
@DirtiesContext
class UssdSmppMenuWalkIT extends UssdMenuWalkSuite {

  static final UssdGatewayStub GATEWAY;

  static {
    try {
      GATEWAY = new UssdGatewayStub();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @DynamicPropertySource
  static void gateway(DynamicPropertyRegistry registry) {
    registry.add("mw.ussd.smpp.mno.robi.port", GATEWAY::port);
  }

  private final MockMvcTester http;
  private final StringRedisTemplate valkey;
  private final MeterRegistry meters;

  @Autowired
  UssdSmppMenuWalkIT(MockMvcTester http, StringRedisTemplate valkey, MeterRegistry meters) {
    this.http = http;
    this.valkey = valkey;
    this.meters = meters;
  }

  @AfterAll
  void stopGateway() throws IOException {
    GATEWAY.close();
  }

  @Override
  protected String instanceId() {
    return "it-ussd-walk-smpp";
  }

  @Override
  protected Walk walk(List<String> inputs) {
    List<UssdGatewayStub.Screen> screens = GATEWAY.dial(0, CUSTOMER, "*268#", inputs);
    assertThat(screens).doesNotContainNull();
    return new Walk(screens.stream().map(UssdGatewayStub.Screen::text).toList(), screens.getLast().endOfSession());
  }

  @Test
  void everyScreenGoesOutOnTheBindThatReceivedTheSession() {
    List<UssdGatewayStub.Screen> screens = GATEWAY.dial(1, CUSTOMER, "*268#", p2p(true, "10", PIN));

    assertThat(screens).hasSize(7).allSatisfy(s -> {
      assertThat(s.bindIndex()).isEqualTo(1);
      assertThat(s.address()).isEqualTo(CUSTOMER);
      assertThat(s.serviceCode()).isEqualTo("*268#");
    });
    assertThat(screens.subList(0, 6)).allSatisfy(s -> {
      assertThat(s.serviceOp()).isEqualTo(SmppUssdMessage.USSR_REQUEST);
      assertThat(s.endOfSession()).isFalse();
    });
    assertThat(screens.getLast().serviceOp()).isEqualTo(SmppUssdMessage.PSSR_RESPONSE);
    assertThat(screens.getLast().endOfSession()).isTrue();
    assertThat(screens.stream().map(UssdGatewayStub.Screen::sessionNumber).distinct()).hasSize(1);
  }

  @Test
  void theHttpEndpointNoLongerServesAnMnoSwitchedToSmpp() {
    var result = http.post().uri("/ussd/robi").contentType(MediaType.APPLICATION_JSON)
        .content("{\"sessionId\":\"x\",\"msisdn\":\"01712345678\",\"type\":\"BEGIN\"}").exchange();

    assertThat(result).hasStatus(404);
  }

  @Test
  void bothBindsAreUpOnTheGauge() {
    assertThat(meters.get("mw.smpp.bind.up").tag("mno", "robi").gauges())
        .hasSize(2)
        .allSatisfy(g -> assertThat(g.value()).isEqualTo(1.0));
  }

  @Test
  void aReleaseByTheSubscriberDropsTheSession() {
    List<UssdGatewayStub.Screen> screens = GATEWAY.dial(0, CUSTOMER, "*268#", List.of("9"));
    int number = screens.getLast().sessionNumber();
    assertThat(valkey.keys("ussd:robi:smpp-" + number + "-*")).hasSize(1);

    GATEWAY.release(0, CUSTOMER, "*268#", number);

    await().atMost(Duration.ofSeconds(5)).until(() -> valkey.keys("ussd:robi:smpp-" + number + "-*").isEmpty());
  }

  @Test
  void aThrottledScreenIsRetriedAndTheWalkGoesOn() {
    GATEWAY.throttleNext(1);
    GATEWAY.genericNackNext(1);
    int refusedBefore = GATEWAY.refusedCount();

    Walk w = walk(List.of("9", "1"));

    assertThat(GATEWAY.refusedCount() - refusedBefore).isEqualTo(2);
    assertThat(w.screens()).last().isEqualTo("Enter recipient Bits number");
    assertThat(Objects.requireNonNull(w.screens()).size()).isEqualTo(3);
  }
}
