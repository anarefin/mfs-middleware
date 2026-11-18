package mfs.mw.audit.dw.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import mfs.mw.audit.dw.TestInfrastructure;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.ProducerFactory;

/**
 * Every record this service publishes carries the spec 6.1 headers, keeps its partition key, and
 * goes through an idempotent {@code acks=all} producer ({@code KafkaTemplate}, ADR D27). A live
 * publish continues the caller's trace; a stored (outbox) record is sent with exactly the headers
 * it was stored with, so it continues the trace of the transaction that wrote it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestInfrastructure.class)
class EventPublisherIT {

  static final String TOPIC = "it.publish.event.v1";

  private final EventPublisher publisher;
  private final ProducerFactory<String, String> producerFactory;
  private final Tracer tracer;

  @Autowired
  EventPublisherIT(EventPublisher publisher, ProducerFactory<String, String> producerFactory, Tracer tracer) {
    this.publisher = publisher;
    this.producerFactory = producerFactory;
    this.tracer = tracer;
  }

  ConsumerRecord<String, String> readOne(String key) {
    List<ConsumerRecord<String, String>> got =
        KafkaTestConsumer.read(
            TestInfrastructure.kafkaBootstrapServers(), TOPIC, 1, Duration.ofSeconds(20), r -> key.equals(r.key()));
    assertThat(got).hasSize(1);
    return got.getFirst();
  }

  @Test
  void publishedRecordCarriesEveryRequiredHeader() {
    publisher.publish(
        TOPIC, "TXN-1", new EventMetadata("corr-1", "TXN-1", "idem-1", 1), Map.of("schemaVersion", 1, "status", "OK"));

    ConsumerRecord<String, String> record = readOne("TXN-1");
    assertThat(record.value()).contains("\"status\":\"OK\"");
    Map<String, String> headers = KafkaTestConsumer.headers(record);
    assertThat(headers)
        .containsEntry("mw-correlation-id", "corr-1")
        .containsEntry("mw-txn-id", "TXN-1")
        .containsEntry("mw-idempotency-key", "idem-1")
        .containsEntry("mw-source", "mw-audit-dw")
        .containsEntry("mw-schema-version", "1");
    assertThat(headers.get("traceparent")).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]");
    assertThat(record.headers().headers("traceparent")).as("one traceparent, not two").hasSize(1);
  }

  @Test
  void aLivePublishContinuesTheCallersTrace() {
    Span business = tracer.nextSpan().name("business operation").start();
    Tracer.SpanInScope scope = tracer.withSpan(business);
    try {
      publisher.publish(TOPIC, "TXN-TRACE", new EventMetadata("corr-t", "TXN-TRACE", "idem-t", 1), Map.of());
    } finally {
      scope.close();
      business.end();
    }

    String traceparent = KafkaTestConsumer.headers(readOne("TXN-TRACE")).get("traceparent");
    assertThat(traceparent.split("-")[1]).isEqualTo(business.context().traceId());
  }

  @Test
  void aStoredRecordIsSentWithExactlyItsStoredHeaders() {
    Map<String, String> stored =
        Map.of(
            "mw-correlation-id", "corr-s",
            "mw-txn-id", "TXN-STORED",
            "mw-idempotency-key", "idem-s",
            "mw-source", "mw-audit-dw",
            "mw-schema-version", "1",
            "traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");

    publisher.publishStoredAsync(TOPIC, "TXN-STORED", stored, "{\"stored\":true}").join();

    ConsumerRecord<String, String> record = readOne("TXN-STORED");
    assertThat(record.value()).isEqualTo("{\"stored\":true}");
    assertThat(KafkaTestConsumer.headers(record)).containsExactlyInAnyOrderEntriesOf(stored);
  }

  @Test
  void aStoredRecordKafkaRefusesCompletesItsFutureExceptionally() {
    String tooLarge = "x".repeat(2 * 1024 * 1024); // above the producer's max.request.size

    assertThat(publisher.publishStoredAsync(TOPIC, "TXN-BIG", Map.of(), tooLarge)).failsWithin(Duration.ofSeconds(30));
  }

  @Test
  void refusesToPublishWithoutRequiredMetadata() {
    assertThatThrownBy(
            () -> publisher.publish(TOPIC, "k", new EventMetadata("corr", "  ", "idem", 1), Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("txnId");
  }

  @Test
  void producerIsIdempotentWithAcksAllAndAtMostFiveInFlight() {
    Map<String, Object> config = producerFactory.getConfigurationProperties();
    assertThat(String.valueOf(config.get(ProducerConfig.ACKS_CONFIG))).isEqualTo("all");
    assertThat(String.valueOf(config.get(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG))).isEqualTo("true");
    assertThat(String.valueOf(config.get(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION))).isEqualTo("5");
  }
}
