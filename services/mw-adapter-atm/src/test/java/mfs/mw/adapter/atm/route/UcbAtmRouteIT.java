package mfs.mw.adapter.atm.route;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import mfs.mw.adapter.atm.AtmFixtures;
import mfs.mw.adapter.atm.AtmTestTopics;
import mfs.mw.adapter.atm.TestInfrastructure;
import mfs.mw.adapter.atm.dto.PartnerReply;
import mfs.mw.adapter.atm.dto.PartnerRequest;
import mfs.mw.adapter.atm.messaging.EventMetadata;
import mfs.mw.adapter.atm.messaging.EventPublisher;
import mfs.mw.adapter.atm.messaging.KafkaTestConsumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Ticket 120a (FRS UC-17, EXT-16): the dev seed {@code ucbatm} (application-dev.yml) asks the ATM
 * switch for the dispense status of a cash-out by our stable reference, signed hmac-sha256.
 * DISPENSED is SUCCESS, NOT_DISPENSED FAILED {@code MW-PTR-UCBATM-<suffix>}, 404 NOT_FOUND, a 5xx
 * UNKNOWN. WireMock stands in for the switch (mw-simulators' UcbAtmMock on the dev stack).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "mw.atm.request-consumer.enabled=true",
      "mw.outbox.relay.enabled=true",
      "mw.outbox.relay.period=100ms",
      "spring.kafka.consumer.group-id=mw-adapter-atm-ucbatm-it"
    })
@Import({TestInfrastructure.class, AtmTestTopics.class})
@DirtiesContext
class UcbAtmRouteIT {

  static final JsonMapper JSON = JsonMapper.builder().build();
  static final WireMockServer SWITCH = new WireMockServer(WireMockConfiguration.options().dynamicPort());
  static final String WALLET = "01711000001";

  static {
    SWITCH.start();
  }

  @DynamicPropertySource
  static void switchUrl(DynamicPropertyRegistry registry) {
    registry.add("mw.partners.instances.ucbatm.base-url", () -> "http://localhost:" + SWITCH.port());
  }

  private final EventPublisher publisher;
  private final CircuitBreakerRegistry breakers;

  @Autowired
  UcbAtmRouteIT(EventPublisher publisher, CircuitBreakerRegistry breakers) {
    this.publisher = publisher;
    this.breakers = breakers;
  }

  @BeforeEach
  void reset() {
    SWITCH.resetAll();
    breakers.circuitBreaker("ucbatm").reset();
  }

  private void stubStatus(String txnId, int status, String body) {
    SWITCH.stubFor(get(urlEqualTo("/ucbatm/v1/dispenses/" + ref(txnId)))
        .willReturn(aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body)));
  }

  private static String ref(String txnId) {
    return "BITS" + txnId.replace("-", "").toUpperCase();
  }

  @Test
  void aDispensedCashOutIsSuccessAskedByOurReferenceSigned() throws Exception {
    String txnId = AtmFixtures.newTxnId();
    stubStatus(txnId, 200, "{\"status\":\"DISPENSED\",\"dispenseRef\":\"dsp-1\",\"code\":\"00\"}");

    PartnerReply reply = send(txnId);

    assertThat(reply.payload().outcome()).isEqualTo("SUCCESS");
    assertThat(reply.payload().partnerRef()).isEqualTo("dsp-1");
    SWITCH.verify(1, getRequestedFor(urlEqualTo("/ucbatm/v1/dispenses/" + ref(txnId)))
        .withHeader("X-Signature", matching("[A-Za-z0-9+/]{43}=")));
  }

  @Test
  void aNotDispensedCashOutIsFailedWithTheMappedCode() throws Exception {
    String txnId = AtmFixtures.newTxnId();
    stubStatus(txnId, 200, "{\"status\":\"NOT_DISPENSED\",\"code\":\"5101\",\"message\":\"jam\"}");

    PartnerReply reply = send(txnId);

    assertThat(reply.payload().outcome()).isEqualTo("FAILED");
    assertThat(reply.payload().errorCode()).isEqualTo("MW-PTR-UCBATM-NOT_DISPENSED");
  }

  @Test
  void anUnknownReferenceIsNotFound() throws Exception {
    String txnId = AtmFixtures.newTxnId();
    stubStatus(txnId, 404, "{\"status\":\"NOT_FOUND\",\"code\":\"5404\"}");

    assertThat(send(txnId).payload().outcome()).isEqualTo("NOT_FOUND");
  }

  @Test
  void aServerErrorIsUnknownNeverAnOutcome() throws Exception {
    String txnId = AtmFixtures.newTxnId();
    stubStatus(txnId, 503, "{\"status\":\"ERROR\"}");

    assertThat(send(txnId).payload().outcome()).isEqualTo("UNKNOWN");
  }

  private PartnerReply send(String txnId) throws Exception {
    PartnerRequest request = new PartnerRequest(
        1, "atm", UUID.randomUUID().toString(), txnId, "corr-0002", AtmFixtures.NOW,
        Instant.now().plusSeconds(300),
        new PartnerRequest.Payload(
            "STATUS_QUERY", "ATM_CASH_OUT", new PartnerRequest.PartnerRef("ucbatm", "ATM", null), "BITS-IGNORED",
            null,
            new PartnerRequest.Party("WALLET", WALLET, null, "CUSTOMER"),
            new PartnerRequest.Party("ORG", "ucb-atm-settlement", null, null),
            null, null));
    publisher.publish("mw.partner.request.atm.v1", txnId,
        new EventMetadata(request.correlationId(), txnId, request.requestId(), 1), request);
    List<ConsumerRecord<String, String>> replies = KafkaTestConsumer.read(
        TestInfrastructure.kafkaBootstrapServers(), "mw.partner.reply.v1", 1, Duration.ofSeconds(30),
        r -> txnId.equals(r.key()));
    assertThat(replies).hasSize(1);
    return JSON.readValue(replies.getFirst().value(), PartnerReply.class);
  }
}
