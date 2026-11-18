package mfs.mw.adapter.billpay.partner.route;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import mfs.mw.adapter.billpay.BillpayFixtures;
import mfs.mw.adapter.billpay.BillpayTestTopics;
import mfs.mw.adapter.billpay.TestInfrastructure;
import mfs.mw.adapter.billpay.contracts.ContractSchemas;
import mfs.mw.adapter.billpay.dto.BillView;
import mfs.mw.adapter.billpay.dto.PartnerReply;
import mfs.mw.adapter.billpay.dto.PartnerRequest;
import mfs.mw.adapter.billpay.messaging.EventMetadata;
import mfs.mw.adapter.billpay.messaging.EventPublisher;
import mfs.mw.adapter.billpay.messaging.KafkaTestConsumer;
import mfs.mw.adapter.billpay.partner.config.PartnerConfigKafkaListener;
import mfs.mw.adapter.billpay.partner.config.PartnerConfigStore;
import mfs.mw.adapter.billpay.service.BillFetchService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import tools.jackson.databind.json.JsonMapper;

/**
 * A new biller with no restart and no Java change (ADR D26, ticket 82 acceptance): a {@code
 * mw.config.changed.v1} record for a partner the service has never seen ({@code waterco}: another
 * host, other JSON field names, an api key) arrives on Kafka; its route is built from the shipped
 * Kamelets, its key is read from OpenBao (Testcontainers, the dev image) through Camel's HashiCorp
 * Vault function, and a bill fetch and a payment against the WireMock biller succeed. The biller
 * catalogue entry is static config, on purpose (D26).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "mw.billpay.request-consumer.enabled=true",
      "mw.partners.config-consumer.enabled=true",
      "mw.outbox.relay.enabled=true",
      "mw.outbox.relay.period=100ms",
      "spring.kafka.consumer.group-id=mw-adapter-billpay-newbiller-it",
      "mw.billers.WATERCO.name=Water Co",
      "mw.billers.WATERCO.category=WATER",
      "mw.billers.WATERCO.partner-id=waterco"
    })
@Import({TestInfrastructure.class, BillpayTestTopics.class})
@DirtiesContext
class NewBillerRouteIT {

  static final String TOKEN = "test-root-token";
  static final String API_KEY = "fake-waterco-api-key";
  static final JsonMapper JSON = JsonMapper.builder().build();
  static final WireMockServer WATERCO = new WireMockServer(WireMockConfiguration.options().dynamicPort());

  static final GenericContainer<?> OPENBAO =
      new GenericContainer<>("openbao/openbao:2.7.2")
          .withCommand("server", "-dev", "-dev-root-token-id=" + TOKEN, "-dev-listen-address=0.0.0.0:8200")
          .withExposedPorts(8200)
          .waitingFor(Wait.forHttp("/v1/sys/health").forStatusCode(200));

  static {
    WATERCO.start();
    OPENBAO.start();
    secret("billagg", Map.of("hmac-secret", "fake-billagg-hmac-secret-for-tests"));
    secret("waterco", Map.of("api-key", API_KEY));
  }

  static String vaultUri() {
    return "http://" + OPENBAO.getHost() + ":" + OPENBAO.getMappedPort(8200);
  }

  static void secret(String partner, Map<String, String> data) {
    try {
      HttpResponse<String> put = HttpClient.newHttpClient().send(
          HttpRequest.newBuilder(URI.create(vaultUri() + "/v1/secret/data/mw-adapter-billpay/" + partner))
              .header("X-Vault-Token", TOKEN)
              .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("data", data))))
              .build(),
          HttpResponse.BodyHandlers.ofString());
      assertThat(put.statusCode()).isEqualTo(200);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @DynamicPropertySource
  static void vault(DynamicPropertyRegistry registry) {
    registry.add("spring.cloud.vault.enabled", () -> "true");
    registry.add("spring.cloud.vault.uri", NewBillerRouteIT::vaultUri);
    registry.add("spring.cloud.vault.authentication", () -> "TOKEN");
    registry.add("spring.cloud.vault.token", () -> TOKEN);
  }

  @Autowired PartnerConfigStore store;
  @Autowired BillFetchService fetch;
  @Autowired EventPublisher publisher;

  /** The new biller's record, as mw-admin-api publishes it: route, mappings, hosts; no Java. */
  static Map<String, Object> waterco(int port) {
    String route = """
        - route:
            from:
              uri: direct:partner-waterco
              steps:
                - choice:
                    when:
                      - simple: "${header.mwOperation} == 'PAY'"
                        steps:
                          - to: "kamelet:mw-map?mapping=PAY.request"
                          - to: "kamelet:mw-http-call?url=http://localhost:%1$d&method=PUT&path=/api/pay&auth=apikey&secretField=api-key&authHeader=X-Water-Key"
                          - to: "kamelet:mw-classify?mapping=PAY.response&definiteStatuses=409"
                      - simple: "${header.mwOperation} == 'INQUIRY'"
                        steps:
                          - to: "kamelet:mw-map?mapping=INQUIRY.request"
                          - to: "kamelet:mw-http-call?url=http://localhost:%1$d&method=POST&path=/api/lookup&auth=apikey&secretField=api-key&authHeader=X-Water-Key"
                          - to: "kamelet:mw-classify?mapping=INQUIRY.response&definiteStatuses=409"
                - to: "kamelet:mw-audit"
        """.formatted(port);
    return Map.of(
        "family", "billpay",
        "allowedHosts", List.of("localhost"),
        "timeoutMs", 3000,
        "route", route,
        "mappings", Map.of(
            "PAY.request", "{\"ref\": reference, \"account\": creditParty.id, \"amt\": amount.major}",
            "PAY.response", "status = 200 and body.result = \"OK\" ? {\"outcome\": \"SUCCESS\", \"partnerRef\": body.receipt}"
                + " : status = 409 ? {\"outcome\": \"FAILED\", \"code\": body.err} : {\"outcome\": \"UNKNOWN\", \"reason\": \"UNEXPECTED_STATUS\"}",
            "INQUIRY.request", "{\"account\": creditParty.id}",
            "INQUIRY.response", "status = 200 ? {\"outcome\": \"SUCCESS\", \"partnerRef\": body.account,"
                + " \"attributes\": {\"amount\": body.due, \"dueDate\": body.by}} : {\"outcome\": \"UNKNOWN\", \"reason\": \"UNEXPECTED_STATUS\"}"),
        "errorMap", Map.of("E1", Map.of("suffix", "ACCOUNT_CLOSED", "retryable", false)));
  }

  @Test
  void aConfigChangedRecordForANewBillerServesAFetchAndAPayWithoutRestartOrJava() throws Exception {
    WATERCO.stubFor(post(urlEqualTo("/api/lookup")).withHeader("X-Water-Key", equalTo(API_KEY))
        .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
            .withBody("{\"account\":\"W-123\",\"due\":\"412.50\",\"by\":\"2026-12-01\"}")));
    WATERCO.stubFor(com.github.tomakehurst.wiremock.client.WireMock.put(urlEqualTo("/api/pay"))
        .withHeader("X-Water-Key", equalTo(API_KEY))
        .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
            .withBody("{\"result\":\"OK\",\"receipt\":\"WC-777\"}")));
    String event = JSON.writeValueAsString(Map.of(
        "schemaVersion", 1, "configKey", "partner/waterco", "configType", "PARTNER", "change", "UPSERT",
        "version", 1, "changedAt", "2026-11-18T10:00:00Z", "changedBy", "ops.maker1", "approvedBy", "ops.checker1",
        "value", waterco(WATERCO.port())));
    assertThat(ContractSchemas.schema("events/mw-admin-api/mw.config.changed.v1.schema.json").validate(JSON.readTree(event)))
        .isEmpty();

    publishConfig("partner/waterco", event);
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (store.instance("waterco").isEmpty() && System.nanoTime() < deadline) {
      Thread.sleep(100);
    }
    assertThat(store.instance("waterco")).isPresent();

    BillView bill = fetch.fetch("WATERCO", "W-123");
    assertThat(bill.amount().valueMinor()).isEqualTo(41_250);
    assertThat(bill.dueDate()).isEqualTo("2026-12-01");

    String txnId = BillpayFixtures.newTxnId();
    PartnerRequest base = BillpayFixtures.pay(txnId, "W-123", 41_250);
    PartnerRequest request = new PartnerRequest(
        base.schemaVersion(), base.family(), UUID.randomUUID().toString(), txnId, base.correlationId(), base.issuedAt(),
        base.deadlineAt(),
        new PartnerRequest.Payload(
            "PAY", "BILL_PAY", new PartnerRequest.PartnerRef("waterco", "BILL", "WATERCO"), "BITS-IGNORED",
            base.payload().amount(), base.payload().debitParty(), base.payload().creditParty(), null, null));
    publisher.publish("mw.partner.request.billpay.v1", txnId,
        new EventMetadata(request.correlationId(), txnId, request.requestId(), 1), request);
    List<ConsumerRecord<String, String>> replies = KafkaTestConsumer.read(
        TestInfrastructure.kafkaBootstrapServers(), "mw.partner.reply.v1", 1, Duration.ofSeconds(30), r -> txnId.equals(r.key()));
    assertThat(replies).hasSize(1);
    PartnerReply reply = JSON.readValue(replies.getFirst().value(), PartnerReply.class);
    assertThat(reply.payload().outcome()).isEqualTo("SUCCESS");
    assertThat(reply.payload().partnerRef()).isEqualTo("WC-777");
    WATERCO.verify(1, com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor(urlEqualTo("/api/pay"))
        .withRequestBody(equalToJson("{\"ref\":\"BITS" + txnId.replace("-", "").toUpperCase() + "\",\"account\":\"W-123\",\"amt\":\"412.50\"}")));
    WATERCO.verify(1, postRequestedFor(urlEqualTo("/api/lookup")));
  }

  private static void publishConfig(String key, String value) throws Exception {
    Properties props = new Properties();
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, TestInfrastructure.kafkaBootstrapServers());
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
      producer.send(new ProducerRecord<>(PartnerConfigKafkaListener.TOPIC, key, value)).get();
    }
  }
}
