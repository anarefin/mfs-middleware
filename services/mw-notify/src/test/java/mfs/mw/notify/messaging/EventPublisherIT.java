package mfs.mw.notify.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import mfs.mw.notify.TestInfrastructure;
import org.apache.camel.CamelContext;
import org.apache.camel.component.kafka.KafkaComponent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Every record this service publishes carries the spec 6.1 headers, keeps its partition key, and
 * goes through an idempotent {@code acks=all} producer.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestInfrastructure.class)
class EventPublisherIT {

  private final EventPublisher publisher;
  private final KafkaContainer kafka;
  private final CamelContext camel;

  @Autowired
  EventPublisherIT(EventPublisher publisher, KafkaContainer kafka, CamelContext camel) {
    this.publisher = publisher;
    this.kafka = kafka;
    this.camel = camel;
  }

  @Test
  void publishedRecordCarriesEveryRequiredHeader() {
    publisher.publish(
        "it.publish.event.v1",
        "TXN-1",
        new EventMetadata("corr-1", "TXN-1", "idem-1", 1),
        Map.of("schemaVersion", 1, "status", "OK"));

    List<ConsumerRecord<String, String>> got =
        KafkaTestConsumer.read(kafka.getBootstrapServers(), "it.publish.event.v1", 1, Duration.ofSeconds(20));

    assertThat(got).hasSize(1);
    ConsumerRecord<String, String> record = got.getFirst();
    assertThat(record.key()).isEqualTo("TXN-1");
    assertThat(record.value()).contains("\"status\":\"OK\"");
    Map<String, String> headers = KafkaTestConsumer.headers(record);
    assertThat(headers)
        .containsEntry("mw-correlation-id", "corr-1")
        .containsEntry("mw-txn-id", "TXN-1")
        .containsEntry("mw-idempotency-key", "idem-1")
        .containsEntry("mw-source", "mw-notify")
        .containsEntry("mw-schema-version", "1");
    assertThat(headers.get("traceparent")).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]");
  }

  @Test
  void refusesToPublishWithoutRequiredMetadata() {
    assertThatThrownBy(
            () ->
                publisher.publish(
                    "it.publish.event.v1", "k", new EventMetadata("corr", "  ", "idem", 1), Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("txnId");
  }

  @Test
  void producerIsIdempotentWithAcksAll() {
    KafkaComponent component = camel.getComponent("kafka", KafkaComponent.class);
    assertThat(component.getConfiguration().getRequestRequiredAcks()).isEqualTo("all");
    assertThat(component.getConfiguration().isEnableIdempotence()).isTrue();
  }
}
