package mfs.mw.adapter.telco.partner.config;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static mfs.mw.adapter.telco.TelcoFixtures.newTxnId;
import static mfs.mw.adapter.telco.TelcoFixtures.pay;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import mfs.mw.adapter.telco.TestInfrastructure;
import mfs.mw.adapter.telco.contracts.ContractSchemas;
import mfs.mw.adapter.telco.dto.PartnerReply;
import mfs.mw.adapter.telco.dto.PartnerRequest;
import mfs.mw.adapter.telco.messaging.EventMetadata;
import mfs.mw.adapter.telco.messaging.EventPublisher;
import mfs.mw.adapter.telco.messaging.KafkaTestConsumer;
import org.apache.camel.CamelContext;
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
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Ticket 38, acceptance criterion 3: an adapter that starts after a partner change picks up the
 * latest snapshot from the compacted {@code mw.config.changed.v1}. The records (robi v1 enabled,
 * then v2 disabled, as mw-admin-api publishes them: full config, key {@code partner/robi}) are on
 * the topic before the config consumer starts; it then starts as on a fresh adapter start (own
 * group, from the beginning) and ends on v2, so a top-up for robi is refused fast and unsent with
 * the mapped {@code MW-SYS-PARTNER_DISABLED}. A later v3 enables it again without a restart.
 */
@CamelSpringBootTest
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "mw.telco.request-consumer.enabled=true",
      "mw.partners.config-consumer.enabled=false",
      "mw.outbox.relay.enabled=true",
      "mw.outbox.relay.period=100ms"
    })
@Import(TestInfrastructure.class)
@DirtiesContext
class PartnerConfigStartupIT {

  static final String CONFIG_SCHEMA = "events/mw-admin-api/mw.config.changed.v1.schema.json";
  static final JsonMapper JSON = JsonMapper.builder().build();
  static final WireMockServer ROBI = new WireMockServer(WireMockConfiguration.options().dynamicPort());

  static {
    ROBI.start();
  }

  private final EventPublisher publisher;
  private final KafkaContainer kafka;
  private final PartnerConfigStore store;
  private final CamelContext camel;

  @Autowired
  PartnerConfigStartupIT(EventPublisher publisher, KafkaContainer kafka, PartnerConfigStore store, CamelContext camel) {
    this.publisher = publisher;
    this.kafka = kafka;
    this.store = store;
    this.camel = camel;
  }

  static String record(long version, boolean enabled) {
    return """
        {"schemaVersion": 1, "configKey": "partner/robi", "configType": "PARTNER", "change": "UPSERT",
         "version": %d, "changedAt": "2026-11-06T10:00:00Z", "changedBy": "ops-maker01", "approvedBy": "ops-checker01",
         "value": {
           "enabled": %s, "template": "rest-json-request-reply", "baseUrl": "http://localhost:%d",
           "connectTimeoutMs": 3000, "timeoutMs": 5000, "auth": {"scheme": "none"},
           "operations": {"PAY": {"method": "POST", "path": "/ocs/v1/topup", "definiteStatuses": [400, 422],
             "request": "{\\"transactionId\\": reference, \\"msisdn\\": creditParty.id, \\"amount\\": amount.major}",
             "response": "status = 200 and body.status = \\"SUCCESS\\" ? {\\"outcome\\": \\"SUCCESS\\", \\"partnerRef\\": body.ocsRef, \\"code\\": body.code} : {\\"outcome\\": \\"UNKNOWN\\", \\"reason\\": \\"UNEXPECTED_STATUS\\"}"}}
         }}
        """
        .formatted(version, enabled, ROBI.port());
  }

  @Test
  void anAdapterStartingAfterTheChangeEndsOnTheLatestSnapshotAndRefusesADisabledPartnerFast() throws Exception {
    stubRobi();
    for (String r : List.of(record(1, true), record(2, false))) {
      assertThat(ContractSchemas.schema(CONFIG_SCHEMA).validate(JSON.readTree(r))).isEmpty();
      publishConfig(r);
    }
    assertThat(store.instance("robi")).as("not known before the consumer starts").isEmpty();

    camel.addRoutes(new PartnerConfigRouteBuilder("mw-adapter-telco"));
    awaitConfig(2);
    assertThat(store.instance("robi").orElseThrow().config().enabled()).isFalse();

    PartnerRequest refused = robiPay();
    long start = System.nanoTime();
    send(refused);
    PartnerReply reply = awaitReply(refused.txnId());
    assertThat(reply.payload().outcome()).isEqualTo("FAILED");
    assertThat(reply.payload().errorCode()).isEqualTo("MW-SYS-PARTNER_DISABLED");
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(10));
    assertThat(ROBI.findAll(postRequestedFor(urlEqualTo("/ocs/v1/topup")))).as("never sent").isEmpty();

    publishConfig(record(3, true));
    awaitConfig(3);
    PartnerRequest accepted = robiPay();
    send(accepted);
    assertThat(awaitReply(accepted.txnId()).payload().outcome()).isEqualTo("SUCCESS");
  }

  private static PartnerRequest robiPay() {
    PartnerRequest base = pay(newTxnId(), "01711000001", 2000);
    PartnerRequest.Payload p = base.payload();
    return new PartnerRequest(
        base.schemaVersion(), base.family(), base.requestId(), base.txnId(), base.correlationId(), base.issuedAt(),
        base.deadlineAt(),
        new PartnerRequest.Payload(
            p.operation(), p.txnType(), new PartnerRequest.PartnerRef("robi", "PREPAID", null), p.partnerTxnRef(),
            p.amount(), p.debitParty(), p.creditParty(), p.originalPartnerRef(), p.attributes()));
  }

  private void awaitConfig(long version) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (System.nanoTime() < deadline) {
      if (store.instance("robi").map(i -> i.version() == version).orElse(false)) {
        return;
      }
      Thread.sleep(100);
    }
    throw new AssertionError("config robi v" + version + " not applied");
  }

  private void publishConfig(String value) throws Exception {
    Properties props = new Properties();
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
      producer.send(new ProducerRecord<>(PartnerConfigRouteBuilder.TOPIC, "partner/robi", value)).get();
    }
  }

  private void stubRobi() {
    ROBI.stubFor(
        post(urlEqualTo("/ocs/v1/topup"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"status\":\"SUCCESS\",\"code\":\"0000\",\"ocsRef\":\"ROBI-1\"}")));
  }

  private void send(PartnerRequest request) {
    publisher.publish(
        "mw.partner.request.telco.v1",
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
