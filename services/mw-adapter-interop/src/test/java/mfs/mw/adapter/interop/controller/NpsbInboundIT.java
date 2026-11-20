package mfs.mw.adapter.interop.controller;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import mfs.mw.adapter.interop.TestInfrastructure;
import mfs.mw.adapter.interop.contracts.ContractSchemas;
import mfs.mw.adapter.interop.dto.Money;
import mfs.mw.adapter.interop.dto.iso.NpsbParty;
import mfs.mw.adapter.interop.dto.iso.NpsbStatus;
import mfs.mw.adapter.interop.dto.iso.NpsbTransfer;
import mfs.mw.adapter.interop.mapping.Iso20022Mapper;
import mfs.mw.adapter.interop.mapping.Iso20022Schemas;
import mfs.mw.adapter.interop.messaging.KafkaTestConsumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Ticket 121b part 2, NPSB_IN (PLT-014): the switch's signed pacs.008 on {@code POST
 * /partner/v1/npsb/inbound} (WireMock as mw-crypto) is published once on {@code mw.partner.inbound.v1}
 * (kind NPSB_CREDIT, txnType NPSB_IN, valid against the contract) and answered with a pacs.002 ACSP; a
 * re-send with the same EndToEndId is answered ACSP again without a second event; a tampered message
 * is refused 401 {@code MW-PTR-NPSB-SIGNATURE}; a credit for another participant is answered RJCT
 * RC01 and not published. The MANUAL_REVIEW after a failed credit is mw-orchestrator's (121c).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "mw.outbox.relay.enabled=true",
      "mw.outbox.relay.period=100ms",
      "mw.interop.crypto.enabled=true",
      "mw.interop.crypto.client-secret=fake-secret",
      "mw.interop.crypto.switch-certificate-pem=-----BEGIN CERTIFICATE-----\\nMIIBfake\\n-----END CERTIFICATE-----"
    })
@Import({TestInfrastructure.class, mfs.mw.adapter.interop.InteropTestTopics.class})
@DirtiesContext
class NpsbInboundIT {

  static final String TOPIC = "mw.partner.inbound.v1";
  static final String SCHEMA = "events/mw-orchestrator/mw.partner.inbound.v1.schema.json";
  static final JsonMapper JSON = JsonMapper.builder().build();
  static final Iso20022Mapper ISO = new Iso20022Mapper(new Iso20022Schemas());
  static final String TAMPER_MARK = "TAMPERED";

  static final WireMockServer CRYPTO =
      new WireMockServer(WireMockConfiguration.options().dynamicPort().globalTemplating(true));

  static {
    CRYPTO.start();
  }

  @DynamicPropertySource
  static void urls(DynamicPropertyRegistry registry) {
    registry.add("mw.interop.crypto.base-url", () -> "http://localhost:" + CRYPTO.port());
    registry.add("mw.interop.crypto.token-uri", () -> "http://localhost:" + CRYPTO.port() + "/token");
  }

  @LocalServerPort int port;

  private final HttpClient http = HttpClient.newHttpClient();

  @BeforeEach
  void stubCrypto() {
    CRYPTO.resetAll();
    CRYPTO.stubFor(post(urlEqualTo("/token")).willReturn(aResponse().withStatus(200)
        .withHeader("Content-Type", "application/json")
        .withBody("{\"access_token\":\"fake-token\",\"expires_in\":300}")));
    CRYPTO.stubFor(post(urlEqualTo("/internal/v1/xmldsig/sign")).willReturn(aResponse().withStatus(200)
        .withHeader("Content-Type", "application/json")
        .withBody("{\"xml\":\"{{jsonPath request.body '$.xml'}}\",\"kid\":\"xmldsig.v1\"}")));
    CRYPTO.stubFor(post(urlEqualTo("/internal/v1/xmldsig/verify")).willReturn(aResponse().withStatus(200)
        .withHeader("Content-Type", "application/json").withBody("{\"valid\":true}")));
  }

  @Test
  void aCreditIsPublishedOnceAndAReSendIsAcceptedAgainWithoutASecondEvent() throws Exception {
    String e2e = e2e();
    String pacs008 = pacs008(e2e, "BITSBDDH", "01711000001", 250_000);

    HttpResponse<String> first = postXml(pacs008);
    HttpResponse<String> second = postXml(pacs008);

    assertThat(first.statusCode()).isEqualTo(200);
    assertThat(second.statusCode()).isEqualTo(200);
    NpsbStatus ack = ISO.readPacs002(first.body());
    assertThat(ack.status()).isEqualTo("ACSP");
    assertThat(ack.originalEndToEndId()).isEqualTo(e2e);
    assertThat(ISO.readPacs002(second.body()).status()).isEqualTo("ACSP");

    List<ConsumerRecord<String, String>> events = KafkaTestConsumer.read(
        TestInfrastructure.kafkaBootstrapServers(), TOPIC, 1, Duration.ofSeconds(20), r -> r.value().contains(e2e));
    assertThat(events).hasSize(1);
    JsonNode event = JSON.readTree(events.getFirst().value());
    assertThat(ContractSchemas.schema(SCHEMA).validate(event)).as(event.toString()).isEmpty();
    assertThat(event.path("family").asString()).isEqualTo("interop");
    assertThat(event.path("partnerId").asString()).isEqualTo("npsb");
    assertThat(event.path("partnerRef").asString()).isEqualTo(e2e);
    assertThat(event.path("payload").path("kind").asString()).isEqualTo("NPSB_CREDIT");
    assertThat(event.path("payload").path("txnType").asString()).isEqualTo("NPSB_IN");
    assertThat(event.path("payload").path("amount").path("valueMinor").asLong()).isEqualTo(250_000);
    assertThat(event.path("payload").path("creditParty").path("id").asString()).isEqualTo("01711000001");
    assertThat(events.getFirst().key()).isEqualTo(event.path("txnId").asString());

    // the relay has had time to send a second one if there were one
    Thread.sleep(1000);
    assertThat(KafkaTestConsumer.readToEnd(TestInfrastructure.kafkaBootstrapServers(), TOPIC,
        r -> r.value().contains(e2e))).hasSize(1);
  }

  @Test
  void aTamperedMessageIsRefusedAndNothingIsPublished() throws Exception {
    String e2e = e2e();
    String tampered = pacs008(e2e, "BITSBDDH", "01711000002", 1_000).replace("</Document>",
        "<!--" + TAMPER_MARK + "--></Document>");
    CRYPTO.stubFor(post(urlEqualTo("/internal/v1/xmldsig/verify")).willReturn(aResponse().withStatus(200)
        .withHeader("Content-Type", "application/json").withBody("{\"valid\":false,\"reason\":\"DIGEST_MISMATCH\"}")));

    HttpResponse<String> res = postXml(tampered);

    assertThat(res.statusCode()).isEqualTo(401);
    assertThat(JSON.readTree(res.body()).path("code").asString()).isEqualTo("MW-PTR-NPSB-SIGNATURE");
    assertThat(KafkaTestConsumer.readToEnd(TestInfrastructure.kafkaBootstrapServers(), TOPIC,
        r -> r.value().contains(e2e))).isEmpty();
  }

  @Test
  void aCreditForAnotherParticipantIsRejectedRc01AndNotPublished() throws Exception {
    String e2e = e2e();

    HttpResponse<String> res = postXml(pacs008(e2e, "FAKEBDD1", "01711000003", 1_000));

    assertThat(res.statusCode()).isEqualTo(200);
    NpsbStatus st = ISO.readPacs002(res.body());
    assertThat(st.status()).isEqualTo("RJCT");
    assertThat(st.reasonCode()).isEqualTo("RC01");
    assertThat(KafkaTestConsumer.readToEnd(TestInfrastructure.kafkaBootstrapServers(), TOPIC,
        r -> r.value().contains(e2e))).isEmpty();
  }

  @Test
  void aMessageThatFailsItsSchemaIsRefused400() throws Exception {
    HttpResponse<String> res = postXml("<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08\"/>");

    assertThat(res.statusCode()).isEqualTo(400);
    assertThat(JSON.readTree(res.body()).path("code").asString()).isEqualTo("MW-PTR-NPSB-INVALID_MESSAGE");
  }

  // ---- helpers ---------------------------------------------------------------------------------

  private static String e2e() {
    return "NPSB" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT).substring(0, 28);
  }

  private static String pacs008(String e2e, String creditorAgent, String wallet, long minor) {
    return ISO.pacs008(new NpsbTransfer("M" + e2e, OffsetDateTime.of(2026, 11, 20, 9, 0, 0, 0, ZoneOffset.UTC), e2e,
        "T" + e2e, new Money(minor, "BDT"), LocalDate.of(2026, 11, 20),
        new NpsbParty("Karim Ahmed", "1234567890123", "FAKEBDD1"),
        new NpsbParty("Rahim Uddin", wallet, creditorAgent)));
  }

  private HttpResponse<String> postXml(String xml) throws Exception {
    return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/partner/v1/npsb/inbound"))
        .header("Content-Type", "application/xml")
        .POST(HttpRequest.BodyPublishers.ofString(xml, StandardCharsets.UTF_8))
        .build(), HttpResponse.BodyHandlers.ofString());
  }
}
