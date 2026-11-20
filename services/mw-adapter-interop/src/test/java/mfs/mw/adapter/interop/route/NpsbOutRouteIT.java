package mfs.mw.adapter.interop.route;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static mfs.mw.adapter.interop.InteropFixtures.forPartner;
import static mfs.mw.adapter.interop.InteropFixtures.newTxnId;
import static mfs.mw.adapter.interop.InteropFixtures.pay;
import static mfs.mw.adapter.interop.InteropFixtures.statusQuery;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import mfs.mw.adapter.interop.TestInfrastructure;
import mfs.mw.adapter.interop.contracts.ContractSchemas;
import mfs.mw.adapter.interop.dto.PartnerReply;
import mfs.mw.adapter.interop.dto.PartnerRequest;
import mfs.mw.adapter.interop.dto.iso.NpsbStatus;
import mfs.mw.adapter.interop.mapping.Iso20022Mapper;
import mfs.mw.adapter.interop.mapping.Iso20022Schemas;
import mfs.mw.adapter.interop.messaging.EventMetadata;
import mfs.mw.adapter.interop.messaging.EventPublisher;
import mfs.mw.adapter.interop.messaging.KafkaTestConsumer;
import org.apache.camel.test.spring.junit6.CamelSpringBootTest;
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
 * Ticket 121b part 2: the {@code npsb} seed partner end to end over Kafka, with WireMock as the NPSB
 * switch (the mw-simulators NpsbMock wire: {@code POST /npsb/v1/transfers|status|returns}
 * {@code {messageType, document}} -> {@code {msgId, document}}) and as mw-crypto (sign echoes the
 * document, verify says valid). pacs.002 ACSC = SUCCESS, RJCT = FAILED mapped by the error-map, no
 * answer in time = UNKNOWN and the pacs.028 inquiry resolves it; the EndToEndId is our reference
 * without {@code BITS} (32 hex, ISO Max35Text).
 */
@CamelSpringBootTest
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "mw.interop.request-consumer.enabled=true",
      "mw.outbox.relay.enabled=true",
      "mw.outbox.relay.period=100ms",
      "mw.interop.crypto.enabled=true",
      "mw.interop.crypto.client-secret=fake-secret",
      "mw.interop.crypto.switch-certificate-pem=-----BEGIN CERTIFICATE-----\\nMIIBfake\\n-----END CERTIFICATE-----",
      "mw.partners.instances.npsb.timeout-ms=1500",
      "mw.partners.instances.npsb.read-timeout-ms=4000"
    })
@Import({TestInfrastructure.class, mfs.mw.adapter.interop.InteropTestTopics.class})
@DirtiesContext
class NpsbOutRouteIT {

  static final String REQUEST_TOPIC = "mw.partner.request.interop.v1";
  static final String REPLY_TOPIC = "mw.partner.reply.v1";
  static final String REPLY_SCHEMA = "events/mw-orchestrator/mw.partner.reply.v1.schema.json";
  static final JsonMapper JSON = JsonMapper.builder().build();
  static final Iso20022Mapper ISO = new Iso20022Mapper(new Iso20022Schemas());
  static final OffsetDateTime AT = OffsetDateTime.of(2026, 11, 20, 9, 0, 0, 0, ZoneOffset.UTC);

  static final WireMockServer SWITCH = new WireMockServer(WireMockConfiguration.options().dynamicPort());
  static final WireMockServer CRYPTO =
      new WireMockServer(WireMockConfiguration.options().dynamicPort().globalTemplating(true));

  static {
    SWITCH.start();
    CRYPTO.start();
  }

  @DynamicPropertySource
  static void urls(DynamicPropertyRegistry registry) {
    registry.add("mw.partners.instances.npsb.base-url", () -> "http://localhost:" + SWITCH.port());
    registry.add("mw.interop.crypto.base-url", () -> "http://localhost:" + CRYPTO.port());
    registry.add("mw.interop.crypto.token-uri", () -> "http://localhost:" + CRYPTO.port() + "/token");
  }

  private final EventPublisher publisher;
  private final CircuitBreakerRegistry breakers;

  @Autowired
  NpsbOutRouteIT(EventPublisher publisher, CircuitBreakerRegistry breakers) {
    this.publisher = publisher;
    this.breakers = breakers;
  }

  @BeforeEach
  void reset() {
    SWITCH.resetAll();
    CRYPTO.resetAll();
    breakers.circuitBreaker("npsb").reset();
    CRYPTO.stubFor(post(urlEqualTo("/token")).willReturn(aResponse().withStatus(200)
        .withHeader("Content-Type", "application/json")
        .withBody("{\"access_token\":\"fake-token\",\"expires_in\":300}")));
    // sign: the document back as it came (the signature itself is mw-crypto's, ticket 65)
    CRYPTO.stubFor(post(urlEqualTo("/internal/v1/xmldsig/sign")).willReturn(aResponse().withStatus(200)
        .withHeader("Content-Type", "application/json")
        .withBody("{\"xml\":\"{{jsonPath request.body '$.xml'}}\",\"kid\":\"xmldsig.v1\"}")));
    CRYPTO.stubFor(post(urlEqualTo("/internal/v1/xmldsig/verify")).willReturn(aResponse().withStatus(200)
        .withHeader("Content-Type", "application/json").withBody("{\"valid\":true}")));
  }

  @Test
  void acscIsSuccessAndTheTransferIsASignedPacs008WithA32HexEndToEndId() throws Exception {
    PartnerRequest request = npsbPay();
    String e2e = e2e(request);
    stubSwitch("/npsb/v1/transfers", 200, answer(pacs002(e2e, "ACSC", null)));

    send(request);
    PartnerReply reply = awaitReply(request.txnId());

    assertThat(reply.partnerId()).isEqualTo("npsb");
    assertThat(reply.payload().outcome()).isEqualTo("SUCCESS");
    assertThat(reply.payload().partnerRef()).isEqualTo("NPSB-CLR-1");
    assertThat(reply.payload().attributes()).containsEntry("partnerTxnRef", "BITS" + e2e);
    List<LoggedRequest> sent = SWITCH.findAll(postRequestedFor(urlEqualTo("/npsb/v1/transfers")));
    assertThat(sent).hasSize(1);
    Map<?, ?> body = JSON.readValue(sent.getFirst().getBodyAsString(), Map.class);
    assertThat(body.get("messageType")).isEqualTo("pacs.008.001.08");
    String document = (String) body.get("document");
    assertThat(ISO.readPacs008(document).endToEndId()).isEqualTo(e2e).hasSize(32);
    assertThat(ISO.readPacs008(document).creditor().agent()).isEqualTo("FAKEBDD1");
    CRYPTO.verify(1, postRequestedFor(urlEqualTo("/internal/v1/xmldsig/sign"))
        .withRequestBody(matchingJsonPath("$.keyId", com.github.tomakehurst.wiremock.client.WireMock.equalTo("xmldsig"))));
    CRYPTO.verify(1, postRequestedFor(urlEqualTo("/internal/v1/xmldsig/verify")));
  }

  @Test
  void rjctIsAFailureMappedByTheErrorMap() throws Exception {
    PartnerRequest request = npsbPay();
    stubSwitch("/npsb/v1/transfers", 200, answer(pacs002(e2e(request), "RJCT", "AC01")));

    send(request);
    PartnerReply reply = awaitReply(request.txnId());

    assertThat(reply.payload().outcome()).isEqualTo("FAILED");
    assertThat(reply.payload().errorCode()).isEqualTo("MW-PTR-NPSB-INVALID_ACCOUNT");
    assertThat(reply.payload().partnerCode()).isEqualTo("AC01");
  }

  @Test
  void aSignatureThatDoesNotVerifyIsNeverBelieved() throws Exception {
    CRYPTO.stubFor(post(urlEqualTo("/internal/v1/xmldsig/verify")).willReturn(aResponse().withStatus(200)
        .withHeader("Content-Type", "application/json").withBody("{\"valid\":false,\"reason\":\"DIGEST_MISMATCH\"}")));
    PartnerRequest request = npsbPay();
    stubSwitch("/npsb/v1/transfers", 200, answer(pacs002(e2e(request), "ACSC", null)));

    send(request);

    assertThat(awaitReply(request.txnId()).payload().outcome()).isEqualTo("UNKNOWN");
  }

  @Test
  void noAnswerInTimeIsUnknownThenThePacs028InquiryResolvesIt() throws Exception {
    PartnerRequest request = npsbPay();
    String e2e = e2e(request);
    SWITCH.stubFor(post(urlEqualTo("/npsb/v1/transfers"))
        .willReturn(aResponse().withStatus(200).withFixedDelay(3000)
            .withHeader("Content-Type", "application/json").withBody(answer(pacs002(e2e, "ACSC", null)))));

    send(request);
    assertThat(awaitReply(request.txnId()).payload().outcome()).isEqualTo("UNKNOWN");

    stubSwitch("/npsb/v1/status", 200, answer(pacs002(e2e, "ACSC", null)));
    PartnerRequest inquiry = forPartner(statusQuery(request.txnId()), "npsb");
    send(inquiry);
    List<PartnerReply> replies = replies(request.txnId(), 2);
    assertThat(replies.get(1).payload().outcome()).isEqualTo("SUCCESS");
    LoggedRequest sent = SWITCH.findAll(postRequestedFor(urlEqualTo("/npsb/v1/status"))).getFirst();
    Map<?, ?> body = JSON.readValue(sent.getBodyAsString(), Map.class);
    assertThat(body.get("messageType")).isEqualTo("pacs.028.001.03");
    assertThat(ISO.readPacs028((String) body.get("document")).originalEndToEndId()).isEqualTo(e2e);
  }

  @Test
  void anInquiryForATransferTheSwitchDoesNotKnowIsNotFound() throws Exception {
    String txnId = newTxnId();
    stubSwitch("/npsb/v1/status", 404, "{\"code\":\"NOT_FOUND\"}");

    send(forPartner(statusQuery(txnId), "npsb"));

    assertThat(awaitReply(txnId).payload().outcome()).isEqualTo("NOT_FOUND");
  }

  // ---- helpers ---------------------------------------------------------------------------------

  private static PartnerRequest npsbPay() {
    return forPartner(pay(newTxnId(), "01911000001", 150_050), "npsb");
  }

  private static String e2e(PartnerRequest request) {
    return request.txnId().replace("-", "").toUpperCase(Locale.ROOT);
  }

  /** The switch's pacs.002 with an enveloped signature element (verified by the mw-crypto stub). */
  private static String pacs002(String e2e, String status, String reason) {
    String xml = ISO.pacs002(new NpsbStatus("NPSB-ST-1", AT, e2e, e2e, e2e, status, reason,
        "ACSC".equals(status) ? "NPSB-CLR-1" : null));
    return xml.replace("</Document>",
        "<ds:Signature xmlns:ds=\"http://www.w3.org/2000/09/xmldsig#\"><ds:SignatureValue>ZmFrZQ==</ds:SignatureValue></ds:Signature></Document>");
  }

  private static String answer(String document) throws Exception {
    return JSON.writeValueAsString(Map.of("msgId", "NPSB-ST-1", "document", document));
  }

  private static void stubSwitch(String path, int status, String body) {
    SWITCH.stubFor(post(urlEqualTo(path)).willReturn(aResponse().withStatus(status)
        .withHeader("Content-Type", "application/json").withBody(body)));
  }

  private void send(PartnerRequest request) {
    publisher.publish(REQUEST_TOPIC, request.txnId(),
        new EventMetadata(request.correlationId(), request.txnId(), request.requestId(), 1), request);
  }

  private PartnerReply awaitReply(String txnId) {
    return replies(txnId, 1).getFirst();
  }

  private List<PartnerReply> replies(String txnId, int count) {
    List<ConsumerRecord<String, String>> records = KafkaTestConsumer.read(
        TestInfrastructure.kafkaBootstrapServers(), REPLY_TOPIC, count, Duration.ofSeconds(30),
        r -> txnId.equals(r.key()));
    assertThat(records).as("replies for %s", txnId).hasSize(count);
    return records.stream().map(r -> {
      assertThat(ContractSchemas.schema(REPLY_SCHEMA).validate(JSON.readTree(r.value()))).as(r.value()).isEmpty();
      return JSON.readValue(r.value(), PartnerReply.class);
    }).toList();
  }
}
