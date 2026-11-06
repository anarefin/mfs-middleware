package mfs.mw.orchestrator.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import mfs.mw.orchestrator.TestInfrastructure;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.testcontainers.kafka.KafkaContainer;

/** The test-only DLQ lookup (ticket 58) against a real broker: a key on any partition is found, others not. */
@SpringJUnitConfig(TestInfrastructure.class)
class DlqClientIT {

  private final String brokers;
  private final DlqClient client;

  @Autowired
  DlqClientIT(KafkaContainer kafka) {
    this.brokers = kafka.getBootstrapServers();
    this.client = new DlqClient(brokers);
  }

  private String topic(int partitions) throws Exception {
    String topic = "it.dlq." + UUID.randomUUID();
    try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, brokers))) {
      admin.createTopics(List.of(new NewTopic(topic, partitions, (short) 1))).all().get();
    }
    return topic;
  }

  @Test
  void findsAParkedKeyOnAnyPartitionAndNothingElse() throws Exception {
    String topic = topic(3);
    try (KafkaProducer<String, String> producer =
        new KafkaProducer<>(
            Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName(),
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName()))) {
      for (int i = 0; i < 20; i++) {
        producer.send(new ProducerRecord<>(topic, "key-" + i, "{}")).get();
      }
    }

    assertThat(client.holds(topic, "key-0")).isTrue();
    assertThat(client.holds(topic, "key-19")).isTrue();
    assertThat(client.holds(topic, "key-20")).isFalse();
  }

  @Test
  void anEmptyDlqHoldsNothing() throws Exception {
    assertThat(client.holds(topic(1), "key-0")).isFalse();
  }
}
