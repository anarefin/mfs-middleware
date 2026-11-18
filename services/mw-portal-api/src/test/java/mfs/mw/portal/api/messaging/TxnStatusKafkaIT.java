package mfs.mw.portal.api.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import mfs.mw.portal.api.TestInfrastructure;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

/**
 * {@code mw.txn.status.v1} reaches {@link TxnStatusListener} over real Kafka (group {@code
 * mw-portal-api}, ADR D27): a record that is not a status message is not retried (it fails the
 * same way again) and lands on {@code mw.txn.status.v1.mw-portal-api.dlq} with its key, its 6.1
 * headers and the {@code mw-*} diagnostics, instead of blocking the partition.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {"mw.portal-api.consumers.enabled=true", "mw.kafka.retry.delays=100ms,100ms,100ms"})
@Import({TestInfrastructure.class, TxnStatusKafkaIT.Topics.class})
@DirtiesContext
class TxnStatusKafkaIT {

  static final String GROUP = "mw-portal-api";

  /** The topic, its retry topics and its DLQ exist before the listener subscribes, as the platform declares them. */
  @TestConfiguration(proxyBeanMethods = false)
  static class Topics {
    @Bean
    String txnStatusTopics() {
      KafkaTestTopics.createWithRetries(TxnStatusListener.TOPIC, GROUP, 3, 1);
      return TxnStatusListener.TOPIC;
    }
  }

  private final EventPublisher publisher;

  @Autowired
  TxnStatusKafkaIT(EventPublisher publisher) {
    this.publisher = publisher;
  }

  @Test
  void aRecordThatIsNotAStatusMessageGoesStraightToTheDlq() {
    String key = UUID.randomUUID().toString();
    publisher.publish(TxnStatusListener.TOPIC, key, new EventMetadata("corr-status", key, key, 1), "not a status message");

    List<ConsumerRecord<String, String>> parked =
        KafkaTestConsumer.read(
            TestInfrastructure.kafkaBootstrapServers(),
            KafkaTestTopics.dlqTopic(TxnStatusListener.TOPIC, GROUP),
            1,
            Duration.ofSeconds(60),
            r -> key.equals(r.key()));

    assertThat(parked).hasSize(1);
    Map<String, String> headers = KafkaTestConsumer.headers(parked.getFirst());
    assertThat(headers)
        .containsEntry(MwHeaders.CORRELATION_ID, "corr-status")
        .containsEntry(MwHeaders.ORIGINAL_TOPIC, TxnStatusListener.TOPIC)
        .containsEntry(MwHeaders.RETRY_ATTEMPT, "1")
        .containsEntry(MwHeaders.ERROR_CLASS, "tools.jackson.databind.exc.MismatchedInputException");
    assertThat(
            KafkaTestConsumer.readToEnd(
                TestInfrastructure.kafkaBootstrapServers(),
                KafkaTestTopics.retryTopic(TxnStatusListener.TOPIC, GROUP, 1),
                r -> key.equals(r.key())))
        .as("not retried")
        .isEmpty();
  }
}
