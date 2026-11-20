package mfs.mw.adapter.card.route;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.notContaining;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import mfs.mw.adapter.card.CardFixtures;
import mfs.mw.adapter.card.CardTestTopics;
import mfs.mw.adapter.card.TestInfrastructure;
import mfs.mw.adapter.card.dto.PartnerReply;
import mfs.mw.adapter.card.dto.PartnerRequest;
import mfs.mw.adapter.card.messaging.EventMetadata;
import mfs.mw.adapter.card.messaging.EventPublisher;
import mfs.mw.adapter.card.messaging.KafkaTestConsumer;
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
 * Ticket 118 (FRS UC-13, EXT-14, FL-07): the dev seed {@code cybersource} (application-dev.yml) maps
 * CREATE_SESSION (INQUIRY), AUTHORISE_CAPTURE (PAY), STATUS_QUERY and REFUND (REVERSE) of an
 * ADD_MONEY_CARD to the acquirer's REST API, every call signed hmac-sha256. A decline or a 3-D Secure
 * failure (422 with a code) is FAILED {@code MW-PTR-CYBERSOURCE-<suffix>}; a 5xx is UNKNOWN, never a
 * capture. Only the session id and the transient token ever leave: no card data. WireMock stands in
 * for the acquirer (mw-simulators' CyberSourceMock on the dev stack).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "mw.card.request-consumer.enabled=true",
      "mw.outbox.relay.enabled=true",
      "mw.outbox.relay.period=100ms",
      "spring.kafka.consumer.group-id=mw-adapter-card-cybersource-it"
    })
@Import({TestInfrastructure.class, CardTestTopics.class})
@DirtiesContext
class CybersourceRouteIT {

  static final JsonMapper JSON = JsonMapper.builder().build();
  static final WireMockServer ACQUIRER = new WireMockServer(WireMockConfiguration.options().dynamicPort());
  static final String WALLET = "01711000001";

  static {
    ACQUIRER.start();
  }

  @DynamicPropertySource
  static void acquirerUrl(DynamicPropertyRegistry registry) {
    registry.add("mw.partners.instances.cybersource.base-url", () -> "http://localhost:" + ACQUIRER.port());
  }

  private final EventPublisher publisher;
  private final CircuitBreakerRegistry breakers;

  @Autowired
  CybersourceRouteIT(EventPublisher publisher, CircuitBreakerRegistry breakers) {
    this.publisher = publisher;
    this.breakers = breakers;
  }

  @BeforeEach
  void reset() {
    ACQUIRER.resetAll();
    breakers.circuitBreaker("cybersource").reset();
  }

  private void stubPost(String path, int status, String body) {
    ACQUIRER.stubFor(post(urlEqualTo(path))
        .willReturn(aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body)));
  }

  @Test
  void aCreateSessionReturnsTheSessionAndTheHostedPageSigned() throws Exception {
    stubPost("/cybersource/v1/sessions", 201,
        "{\"status\":\"CREATED\",\"sessionId\":\"sess-1\",\"hostedPageUrl\":\"https://pay.example/hp/sess-1\"}");
    String txnId = CardFixtures.newTxnId();

    PartnerReply reply = send("INQUIRY", txnId, 50_000L, null);

    assertThat(reply.payload().outcome()).isEqualTo("SUCCESS");
    assertThat(reply.payload().partnerRef()).isEqualTo("sess-1");
    assertThat(reply.payload().attributes())
        .containsEntry("hostedPageUrl", "https://pay.example/hp/sess-1")
        .containsEntry("sessionId", "sess-1");
    String ref = "BITS" + txnId.replace("-", "").toUpperCase();
    ACQUIRER.verify(1, postRequestedFor(urlEqualTo("/cybersource/v1/sessions"))
        .withHeader("X-Signature", matching("[A-Za-z0-9+/]{43}="))
        .withRequestBody(matchingJsonPath("$.clientReference", equalTo(ref)))
        .withRequestBody(matchingJsonPath("$.amount", equalTo("500.00")))
        .withRequestBody(notContaining(WALLET)));
  }

  @Test
  void anAuthoriseCaptureSendsOnlyTheSessionAndTheTokenAndSucceedsWhenCaptured() throws Exception {
    stubPost("/cybersource/v1/payments", 201, "{\"status\":\"CAPTURED\",\"paymentId\":\"pay-1\",\"code\":\"100\","
        + "\"amount\":\"500.00\",\"currency\":\"BDT\",\"clientReference\":\"BITS-ECHO\"}");

    PartnerReply reply = send("PAY", CardFixtures.newTxnId(), 50_000L,
        Map.of("sessionId", "sess-1", "transientToken", "tok-fake-0001"));

    assertThat(reply.payload().outcome()).isEqualTo("SUCCESS");
    assertThat(reply.payload().partnerRef()).isEqualTo("pay-1");
    // what was captured goes back to the orchestrator, which credits only its own amount and reference
    assertThat(reply.payload().attributes())
        .containsEntry("amount", "500.00").containsEntry("currency", "BDT").containsEntry("clientReference", "BITS-ECHO")
        .doesNotContainKey("transientToken");
    ACQUIRER.verify(1, postRequestedFor(urlEqualTo("/cybersource/v1/payments"))
        .withRequestBody(matchingJsonPath("$.sessionId", equalTo("sess-1")))
        .withRequestBody(matchingJsonPath("$.transientToken", equalTo("tok-fake-0001")))
        .withRequestBody(notContaining("4111")));
  }

  @Test
  void aDeclineIsFailedWithTheMappedCode() throws Exception {
    stubPost("/cybersource/v1/payments", 422, "{\"status\":\"DECLINED\",\"code\":\"4001\",\"message\":\"declined\"}");

    PartnerReply reply = send("PAY", CardFixtures.newTxnId(), 50_000L,
        Map.of("sessionId", "sess-2", "transientToken", "tok-fake-0002"));

    assertThat(reply.payload().outcome()).isEqualTo("FAILED");
    assertThat(reply.payload().errorCode()).isEqualTo("MW-PTR-CYBERSOURCE-CARD_DECLINED");
  }

  @Test
  void aThreeDsFailureIsFailedWithTheMappedCode() throws Exception {
    stubPost("/cybersource/v1/payments", 422, "{\"status\":\"DECLINED\",\"code\":\"4002\",\"message\":\"3ds failed\"}");

    PartnerReply reply = send("PAY", CardFixtures.newTxnId(), 50_000L,
        Map.of("sessionId", "sess-3", "transientToken", "tok-fake-0003"));

    assertThat(reply.payload().outcome()).isEqualTo("FAILED");
    assertThat(reply.payload().errorCode()).isEqualTo("MW-PTR-CYBERSOURCE-THREE_DS_FAILED");
  }

  @Test
  void aServerErrorOnCaptureIsUnknownNeverACapture() throws Exception {
    stubPost("/cybersource/v1/payments", 503, "{\"status\":\"ERROR\"}");

    PartnerReply reply = send("PAY", CardFixtures.newTxnId(), 50_000L,
        Map.of("sessionId", "sess-4", "transientToken", "tok-fake-0004"));

    assertThat(reply.payload().outcome()).isEqualTo("UNKNOWN");
  }

  @Test
  void aStatusQueryAsksByOurReference() throws Exception {
    ACQUIRER.stubFor(get(urlMatching("/cybersource/v1/payments/BITS.*"))
        .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
            .withBody("{\"status\":\"CAPTURED\",\"paymentId\":\"pay-5\"}")));
    String txnId = CardFixtures.newTxnId();

    PartnerReply reply = send("STATUS_QUERY", txnId, null, null);

    assertThat(reply.payload().outcome()).isEqualTo("SUCCESS");
    assertThat(reply.payload().partnerRef()).isEqualTo("pay-5");
    ACQUIRER.verify(1, getRequestedFor(
        urlEqualTo("/cybersource/v1/payments/BITS" + txnId.replace("-", "").toUpperCase())));
  }

  @Test
  void aRefundCarriesTheRefundIdOnTheOriginalReference() throws Exception {
    String txnId = CardFixtures.newTxnId();
    String ref = "BITS" + txnId.replace("-", "").toUpperCase();
    stubPost("/cybersource/v1/payments/" + ref + "/refunds", 201, "{\"status\":\"REFUNDED\",\"refundId\":\"rf-1\"}");

    PartnerReply reply = send("REVERSE", txnId, 50_000L, null);

    assertThat(reply.payload().outcome()).isEqualTo("SUCCESS");
    assertThat(reply.payload().partnerRef()).isEqualTo("rf-1");
    ACQUIRER.verify(1, postRequestedFor(urlEqualTo("/cybersource/v1/payments/" + ref + "/refunds"))
        .withRequestBody(containing("reverse:" + txnId)));
  }

  private PartnerReply send(String operation, String txnId, Long valueMinor, Map<String, String> attributes)
      throws Exception {
    PartnerRequest request = new PartnerRequest(
        1, "card", UUID.randomUUID().toString(), txnId, "corr-0002", CardFixtures.NOW,
        Instant.now().plusSeconds(300),
        new PartnerRequest.Payload(
            operation, "ADD_MONEY_CARD", new PartnerRequest.PartnerRef("cybersource", "CARD", null), "BITS-IGNORED",
            valueMinor == null ? null : new PartnerRequest.Amount(valueMinor, "BDT"),
            new PartnerRequest.Party("CARD_TOKEN", "sess-fake", null, null),
            new PartnerRequest.Party("WALLET", WALLET, null, "CUSTOMER"),
            null, attributes));
    publisher.publish("mw.partner.request.card.v1", txnId,
        new EventMetadata(request.correlationId(), txnId, request.requestId(), 1), request);
    List<ConsumerRecord<String, String>> replies = KafkaTestConsumer.read(
        TestInfrastructure.kafkaBootstrapServers(), "mw.partner.reply.v1", 1, Duration.ofSeconds(30),
        r -> txnId.equals(r.key()));
    assertThat(replies).hasSize(1);
    return JSON.readValue(replies.getFirst().value(), PartnerReply.class);
  }
}
