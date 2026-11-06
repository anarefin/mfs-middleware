package mfs.mw.adapter.bank.partner.config;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static mfs.mw.adapter.bank.BankFixtures.newTxnId;
import static mfs.mw.adapter.bank.BankFixtures.pay;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import mfs.mw.adapter.bank.TestInfrastructure;
import mfs.mw.adapter.bank.contracts.ContractSchemas;
import mfs.mw.adapter.bank.dto.PartnerReply;
import mfs.mw.adapter.bank.dto.PartnerRequest;
import mfs.mw.adapter.bank.messaging.EventMetadata;
import mfs.mw.adapter.bank.messaging.EventPublisher;
import mfs.mw.adapter.bank.messaging.KafkaTestConsumer;
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
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Partner config without restart (ticket 20, spec 20.4): a {@code mw.config.changed.v1} PARTNER
 * record on Kafka replaces the {@code c2wbank} seed (contract-valid, checked against the
 * schema), with another request mapping and a lower timeout, and the next debit uses
 * both, while the running service keeps consuming {@code mw.partner.request.bank.v1}. Real
 * Kafka and PostgreSQL (Testcontainers), bank stubbed with WireMock.
 */
@CamelSpringBootTest
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "mw.bank.request-consumer.enabled=true",
      "mw.partners.config-consumer.enabled=true",
      "mw.outbox.relay.enabled=true",
      "mw.outbox.relay.period=100ms"
    })
@Import(TestInfrastructure.class)
@DirtiesContext
class PartnerConfigReloadIT {

  static final String CONFIG_SCHEMA = "events/mw-admin-api/mw.config.changed.v1.schema.json";
  static final JsonMapper JSON = JsonMapper.builder().build();
  static final WireMockServer BANK = new WireMockServer(WireMockConfiguration.options().dynamicPort());

  static {
    BANK.start();
  }

  @DynamicPropertySource
  static void bankUrl(DynamicPropertyRegistry registry) {
    registry.add("mw.partners.instances.c2wbank.base-url", () -> "http://localhost:" + BANK.port());
  }

  private final EventPublisher publisher;
  private final KafkaContainer kafka;
  private final PartnerConfigStore store;

  @Autowired
  PartnerConfigReloadIT(EventPublisher publisher, KafkaContainer kafka, PartnerConfigStore store) {
    this.publisher = publisher;
    this.kafka = kafka;
    this.store = store;
  }

  @Test
  void aConfigChangedRecordChangesTheMappingAndTimeoutOfTheNextCallWithoutRestart() throws Exception {
    stubBank(0);
    PartnerRequest before = pay(newTxnId(), "1234567890123", 2000);
    send(before);
    assertThat(awaitReply(before.txnId()).payload().outcome()).isEqualTo("SUCCESS");

    String event =
        """
        {"schemaVersion": 1, "configKey": "partner/c2wbank", "configType": "PARTNER", "change": "UPSERT",
         "version": 1, "changedAt": "2026-11-04T10:00:00Z", "changedBy": "ops.maker1", "approvedBy": "ops.checker1",
         "value": {
           "template": "rest-json-request-reply", "baseUrl": "http://localhost:%d", "timeoutMs": 700,
           "errorMap": {"4002": {"suffix": "DEBIT_REFUSED", "retryable": false}},
           "operations": {"PAY": {"method": "POST", "path": "/c2wbank/v1/debits", "definiteStatuses": [400, 422],
             "request": "{\\"transactionId\\": reference, \\"accountNumber\\": debitParty.id, \\"amount\\": amount.major, \\"channel\\": \\"Bits\\"}",
             "response": "status = 200 and body.status = \\"SUCCESS\\" ? {\\"outcome\\": \\"SUCCESS\\", \\"partnerRef\\": body.debitRef, \\"code\\": body.code} : {\\"outcome\\": \\"UNKNOWN\\", \\"reason\\": \\"UNEXPECTED_STATUS\\"}"}}
         }}
        """
            .formatted(BANK.port());
    assertThat(ContractSchemas.schema(CONFIG_SCHEMA).validate(JSON.readTree(event))).isEmpty();
    publishConfig("partner/c2wbank", event);
    awaitConfig("c2wbank", 1);

    PartnerRequest mapped = pay(newTxnId(), "1234567890123", 2000);
    send(mapped);
    assertThat(awaitReply(mapped.txnId()).payload().outcome()).isEqualTo("SUCCESS");
    String lastBody = BANK.findAll(postRequestedFor(urlEqualTo("/c2wbank/v1/debits"))).getLast().getBodyAsString();
    assertThat(JSON.readTree(lastBody).get("channel").asString()).isEqualTo("Bits");

    BANK.resetAll();
    stubBank(1500);
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
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
      producer.send(new ProducerRecord<>(PartnerConfigRouteBuilder.TOPIC, key, value)).get();
    }
  }

  private void stubBank(int delayMs) {
    BANK.stubFor(
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
        "mw.partner.request.bank.v1",
        request.txnId(),
        new EventMetadata(request.correlationId(), request.txnId(), request.requestId(), 1),
        request);
  }

  private PartnerReply awaitReply(String txnId) {
    List<ConsumerRecord<String, String>> records =
        KafkaTestConsumer.read(
            kafka.getBootstrapServers(), "mw.partner.reply.v1", 1, Duration.ofSeconds(30), r -> txnId.equals(r.key()));
    assertThat(records).as("reply for %s", txnId).hasSize(1);
    return JSON.readValue(records.getFirst().value(), PartnerReply.class);
  }
}
