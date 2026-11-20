package mfs.mw.adapter.merchant.partner.config;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static mfs.mw.adapter.merchant.MerchantFixtures.newTxnId;
import static mfs.mw.adapter.merchant.MerchantFixtures.pay;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import mfs.mw.adapter.merchant.TestInfrastructure;
import mfs.mw.adapter.merchant.contracts.ContractSchemas;
import mfs.mw.adapter.merchant.dto.PartnerReply;
import mfs.mw.adapter.merchant.dto.PartnerRequest;
import mfs.mw.adapter.merchant.messaging.EventMetadata;
import mfs.mw.adapter.merchant.messaging.EventPublisher;
import mfs.mw.adapter.merchant.messaging.KafkaTestConsumer;
import org.apache.camel.test.spring.junit6.CamelSpringBootTest;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * Partner config without restart (ticket 20, spec 20.4): a {@code mw.config.changed.v1} PARTNER
 * record on Kafka replaces the {@code c2wbank} seed (contract-valid, checked against the schema), with
 * another request mapping and a lower timeout, and the next debit uses both, while the running
 * service keeps consuming {@code mw.partner.request.merchant.v1}. Real Kafka and PostgreSQL
 * (Testcontainers), the bank stubbed with WireMock.
 */
@CamelSpringBootTest
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "mw.merchant.request-consumer.enabled=true",
      "mw.partners.config-consumer.enabled=true",
      "mw.outbox.relay.enabled=true",
      "mw.outbox.relay.period=100ms"
    })
@Import({TestInfrastructure.class, mfs.mw.adapter.merchant.MerchantTestTopics.class})
@DirtiesContext
class PartnerConfigReloadIT {

  static final String CONFIG_SCHEMA = "events/mw-admin-api/mw.config.changed.v1.schema.json";
  static final JsonMapper JSON = JsonMapper.builder().build();
  static final WireMockServer PARTNER = new WireMockServer(WireMockConfiguration.options().dynamicPort());

  static {
    PARTNER.start();
  }

  @DynamicPropertySource
  static void partnerUrl(DynamicPropertyRegistry registry) {
    registry.add("mw.partners.instances.c2wbank.base-url", () -> "http://localhost:" + PARTNER.port());
  }

  private final EventPublisher publisher;
  private final PartnerConfigStore store;

  @Autowired
  PartnerConfigReloadIT(EventPublisher publisher, PartnerConfigStore store) {
    this.publisher = publisher;
    this.store = store;
  }

  @Test
  void aConfigChangedRecordChangesTheMappingAndTimeoutOfTheNextCallWithoutRestart() throws Exception {
    stubPartner(0);
    PartnerRequest before = pay(newTxnId(), "1234567890123", 2000);
    send(before);
    assertThat(awaitReply(before.txnId()).payload().outcome()).isEqualTo("SUCCESS");

    String route =
        """
        - route:
            from:
              uri: direct:partner-c2wbank
              steps:
                - to: "kamelet:mw-map?mapping=PAY.request"
                - to: "kamelet:mw-http-call?url=http://localhost:%d&method=POST&path=/c2wbank/v1/debits&auth=none"
                - to: "kamelet:mw-classify?mapping=PAY.response&definiteStatuses=400,422"
                - to: "kamelet:mw-audit"
        """
            .formatted(PARTNER.port());
    Map<String, Object> value = new java.util.LinkedHashMap<>();
    value.put("family", "bank");
    value.put("route", route);
    value.put("mappings", Map.of(
        "PAY.request",
        "{\"transactionId\": reference, \"accountNumber\": debitParty.id, \"amount\": amount.major, \"channel\": \"Bits\"}",
        "PAY.response",
        "status = 200 and body.status = \"SUCCESS\" ? {\"outcome\": \"SUCCESS\", \"partnerRef\": body.debitRef, \"code\": body.code} : {\"outcome\": \"UNKNOWN\", \"reason\": \"UNEXPECTED_STATUS\"}"));
    value.put("allowedHosts", List.of("localhost"));
    value.put("timeoutMs", 700);
    value.put("errorMap", Map.of("4002", Map.of("suffix", "DEBIT_REFUSED", "retryable", false)));
    Map<String, Object> record = new java.util.LinkedHashMap<>();
    record.put("schemaVersion", 1);
    record.put("configKey", "partner/c2wbank");
    record.put("configType", "PARTNER");
    record.put("change", "UPSERT");
    record.put("version", 1);
    record.put("changedAt", "2026-11-04T10:00:00Z");
    record.put("changedBy", "ops.maker1");
    record.put("approvedBy", "ops.checker1");
    record.put("value", value);
    String event = JSON.writeValueAsString(record);
    assertThat(ContractSchemas.schema(CONFIG_SCHEMA).validate(JSON.readTree(event))).isEmpty();
    publishConfig("partner/c2wbank", event);
    awaitConfig("c2wbank", 1);

    PartnerRequest mapped = pay(newTxnId(), "1234567890123", 2000);
    send(mapped);
    assertThat(awaitReply(mapped.txnId()).payload().outcome()).isEqualTo("SUCCESS");
    String lastBody = PARTNER.findAll(postRequestedFor(urlEqualTo("/c2wbank/v1/debits"))).getLast().getBodyAsString();
    assertThat(JSON.readTree(lastBody).get("channel").asString()).isEqualTo("Bits");

    PARTNER.resetAll();
    stubPartner(1500);
    PartnerRequest slow = pay(newTxnId(), "1234567890123", 2000);
    send(slow);
    PartnerReply reply = awaitReply(slow.txnId());
    assertThat(reply.payload().outcome()).isEqualTo("UNKNOWN");
    assertThat(reply.payload().errorCode()).isEqualTo("MW-TMO-PARTNER_TIMEOUT");
  }

  private void awaitConfig(String id, long version) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (System.nanoTime() < deadline) {
      if (store.instance(id).map(i -> i.version() == version).orElse(false)) {
        return;
      }
      Thread.sleep(100);
    }
    throw new AssertionError("config " + id + " v" + version + " not applied");
  }

  private void publishConfig(String key, String value) throws Exception {
    Properties props = new Properties();
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, TestInfrastructure.kafkaBootstrapServers());
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
      producer.send(new ProducerRecord<>(PartnerConfigKafkaListener.TOPIC, key, value)).get();
    }
  }

  private void stubPartner(int delayMs) {
    PARTNER.stubFor(
        post(urlEqualTo("/c2wbank/v1/debits"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withFixedDelay(delayMs)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"status\":\"SUCCESS\",\"code\":\"0000\",\"debitRef\":\"C2W-1\"}")));
  }

  private void send(PartnerRequest request) {
    publisher.publish(
        "mw.partner.request.merchant.v1",
        request.txnId(),
        new EventMetadata(request.correlationId(), request.txnId(), request.requestId(), 1),
        request);
  }

  private PartnerReply awaitReply(String txnId) {
    List<ConsumerRecord<String, String>> records =
        KafkaTestConsumer.read(
            TestInfrastructure.kafkaBootstrapServers(), "mw.partner.reply.v1", 1, Duration.ofSeconds(30), r -> txnId.equals(r.key()));
    assertThat(records).as("reply for %s", txnId).hasSize(1);
    return JSON.readValue(records.getFirst().value(), PartnerReply.class);
  }
}
