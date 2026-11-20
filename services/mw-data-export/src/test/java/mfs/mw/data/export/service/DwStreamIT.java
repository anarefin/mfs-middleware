package mfs.mw.data.export.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import mfs.mw.data.export.TestInfrastructure;
import mfs.mw.data.export.messaging.KafkaTestConsumer;
import mfs.mw.data.export.messaging.KafkaTestTopics;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A completed transaction is published as state-changed and completed, with party tokens and no
 * clear MSISDN (ticket 128).
 */
@SpringBootTest(properties = {"mw.export.consumers-enabled=true", "mw.outbox.relay.enabled=true"})
@Import({TestInfrastructure.class, DwStreamIT.Stubs.class})
@DirtiesContext
class DwStreamIT {

  static final String PAYER = "01712345678";
  static final String PAYEE = "01812345678";

  @DynamicPropertySource
  static void topics(DynamicPropertyRegistry registry) {
    for (String topic : List.of("mw.txn.status.v1", "mw.notify.status.v1", "mw.audit.event.v1",
        "mw.recon.exception.v1", "mw.kyc.completed.v1")) {
      KafkaTestTopics.createWithRetries(topic, "mw-data-export", 3, 1);
    }
    registry.add("mw.export.consumers-enabled", () -> "true");
  }

  @org.springframework.boot.test.context.TestConfiguration
  static class Stubs {
    @Bean
    @Primary
    FieldTokeniser fieldTokeniser() {
      return (domain, value) -> switch (value) {
        case PAYER -> "MSISDN_PAYER";
        case PAYEE -> "MSISDN_PAYEE";
        default -> "MSISDN_OTHER";
      };
    }
  }

  private final JdbcTemplate jdbc;

  @Autowired
  DwStreamIT(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Test
  void aCompletedTransactionIsProjectedWithoutTheClearMsisdn() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(
        org.testcontainers.DockerClientFactory.instance().isDockerAvailable());
    String txnId = UUID.randomUUID().toString();
    String body = """
        {"schemaVersion":1,"txnId":"%s","correlationId":"corr-128","channel":"APP","txnType":"P2P",
         "status":"COMPLETED","previousStatus":"POSTING",
         "debitParty":{"type":"WALLET","id":"%s"},
         "creditParty":{"type":"WALLET","id":"%s"},
         "amount":{"valueMinor":100,"currency":"BDT"},
         "requestedAt":"2026-11-20T02:00:00Z","updatedAt":"2026-11-20T02:00:05Z",
         "completedAt":"2026-11-20T02:00:05Z"}
        """.formatted(txnId, PAYER, PAYEE);
    try (KafkaProducer<String, String> producer = new KafkaProducer<>(java.util.Map.of(
        ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, TestInfrastructure.kafkaBootstrapServers(),
        ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName(),
        ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName()))) {
      producer.send(new ProducerRecord<>("mw.txn.status.v1", txnId, body)).get();
    }
    String bootstrap = TestInfrastructure.kafkaBootstrapServers();
    var state = KafkaTestConsumer.read(bootstrap, "dw.txn.state-changed.v1", 1, Duration.ofSeconds(20));
    var done = KafkaTestConsumer.read(bootstrap, "dw.txn.completed.v1", 1, Duration.ofSeconds(5));
    assertThat(state).hasSize(1);
    assertThat(done).hasSize(1);
    assertThat(state.get(0).value()).contains("MSISDN_PAYER").doesNotContain(PAYER).doesNotContain(PAYEE);
    assertThat(done.get(0).value()).contains("MSISDN_PAYEE").doesNotContain(PAYER).doesNotContain(PAYEE);
    String stored = jdbc.queryForObject("select payer_token from txn_fact where txn_id = ?", String.class, txnId);
    assertThat(stored).isEqualTo("MSISDN_PAYER");
  }
}
