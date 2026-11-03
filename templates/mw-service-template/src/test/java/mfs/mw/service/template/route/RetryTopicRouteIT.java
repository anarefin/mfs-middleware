package mfs.mw.service.template.route;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import mfs.mw.service.template.TestInfrastructure;
import mfs.mw.service.template.config.KafkaRetryProperties;
import mfs.mw.service.template.messaging.EventMetadata;
import mfs.mw.service.template.messaging.EventPublisher;
import mfs.mw.service.template.messaging.KafkaTestConsumer;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.component.kafka.KafkaConstants;
import org.apache.camel.test.spring.junit6.CamelSpringBootTest;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Kafka consumer errors go to non-blocking retry topics, then the DLQ (spec 19.2, 6.2): a record
 * that keeps failing travels {@code <topic>.retry.1 -> .retry.2 -> .retry.3 -> .dlq} with its key
 * and 6.1 headers kept; a record that succeeds on a retry never reaches the DLQ. Delays are
 * shortened to 2 s here; production uses 10 s, 1 min, 10 min. Holding a record back never blocks
 * the consumer thread, and a forged {@code mw-retry-not-before} cannot stall it.
 */
@CamelSpringBootTest
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = "mw.kafka.retry.delays=2s,2s,2s")
@Import({TestInfrastructure.class, RetryTopicRouteIT.Routes.class})
@ExtendWith(OutputCaptureExtension.class)
class RetryTopicRouteIT {

  static final String TOPIC = "it.retry.event.v1";
  static final String SECRET_MSISDN = "01712345789";

  /** A test listener: records with "fail-always" always throw, "fail-once" throw on first try. */
  static class FlakyHandler {
    final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
    final List<String> succeeded = new CopyOnWriteArrayList<>();

    public void handle(Exchange exchange) {
      String key = exchange.getMessage().getHeader(KafkaConstants.KEY, String.class);
      int call = calls.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
      String body = exchange.getMessage().getBody(String.class);
      // A listener may change the body before failing; the retry must carry the original.
      exchange.getMessage().setBody("changed by handler");
      if (body.contains("fail-always") || (body.contains("fail-once") && call == 1)) {
        throw new IllegalStateException("handler failed for " + body);
      }
      succeeded.add(key);
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class Routes {
    @Bean
    FlakyHandler flakyHandler() {
      return new FlakyHandler();
    }

    @Bean
    RetryTopicRouteBuilder itRoute(KafkaRetryProperties retry) {
      return new RetryTopicRouteBuilder(retry) {
        @Override
        protected String topic() {
          return TOPIC;
        }

        @Override
        protected String handlerUri() {
          return "bean:flakyHandler?method=handle";
        }
      };
    }
  }

  @Autowired CamelContext camel;
  @Autowired EventPublisher publisher;
  @Autowired KafkaContainer kafka;
  @Autowired FlakyHandler handler;

  List<ConsumerRecord<String, String>> recordsFor(String topic, String key, int count) {
    return recordsFor(topic, key, count, Duration.ofSeconds(30));
  }

  List<ConsumerRecord<String, String>> recordsFor(
      String topic, String key, int count, Duration timeout) {
    return KafkaTestConsumer.read(
        kafka.getBootstrapServers(), topic, count, timeout, r -> key.equals(r.key()));
  }

  static String header(ConsumerRecord<?, ?> r, String name) {
    Header h = r.headers().lastHeader(name);
    return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
  }

  static void await(BooleanSupplier condition, Duration timeout) throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("condition not met within " + timeout);
      }
      Thread.sleep(100);
    }
  }

  @Test
  void failingRecordGoesThroughEachRetryTopicThenDlqWithHeadersKept(CapturedOutput output) {
    String body = "{\"schemaVersion\":1,\"case\":\"fail-always\",\"msisdn\":\"" + SECRET_MSISDN + "\"}";
    publisher.publish(TOPIC, "K-ALWAYS", new EventMetadata("corr-a", "TXN-A", "idem-a", 1), rawJson(body));

    List<ConsumerRecord<String, String>> dlq = recordsFor(TOPIC + ".dlq", "K-ALWAYS", 1);
    assertThat(dlq).hasSize(1);

    ConsumerRecord<String, String> previous = null;
    for (int n = 1; n <= 3; n++) {
      List<ConsumerRecord<String, String>> retry = recordsFor(TOPIC + ".retry." + n, "K-ALWAYS", 1);
      assertThat(retry).as("record on retry.%d", n).hasSize(1);
      ConsumerRecord<String, String> r = retry.getFirst();
      assertThat(header(r, "mw-retry-attempt")).isEqualTo(Integer.toString(n));
      assertThat(r.value()).isEqualTo(body);
      if (previous != null) {
        assertThat(r.timestamp() - previous.timestamp())
            .as("retry.%d waits for its delay", n)
            .isGreaterThanOrEqualTo(2_000);
      }
      previous = r;
    }

    ConsumerRecord<String, String> parked = dlq.getFirst();
    assertThat(parked.key()).isEqualTo("K-ALWAYS");
    assertThat(parked.value()).isEqualTo(body);
    assertThat(header(parked, "mw-correlation-id")).isEqualTo("corr-a");
    assertThat(header(parked, "mw-txn-id")).isEqualTo("TXN-A");
    assertThat(header(parked, "mw-idempotency-key")).isEqualTo("idem-a");
    assertThat(header(parked, "mw-source")).isEqualTo("mw-service-template");
    assertThat(header(parked, "mw-schema-version")).isEqualTo("1");
    assertThat(header(parked, "traceparent")).isNotBlank();
    assertThat(header(parked, "mw-retry-attempt")).isEqualTo("4");
    assertThat(header(parked, "mw-original-topic")).isEqualTo(TOPIC);
    assertThat(header(parked, "mw-error-class")).isEqualTo(IllegalStateException.class.getName());
    assertThat(handler.calls.get("K-ALWAYS")).hasValue(4);

    // Retry and DLQ logging never shows the payload or the exception message (18.3).
    assertThat(output.getAll()).contains("kafka.dlq.parked").doesNotContain(SECRET_MSISDN);
  }

  @Test
  void recordThatSucceedsOnRetryIsNotSentToDlq() throws Exception {
    publisher.publish(
        TOPIC, "K-ONCE", new EventMetadata("corr-b", "TXN-B", "idem-b", 1), rawJson("{\"case\":\"fail-once\"}"));

    await(() -> handler.succeeded.contains("K-ONCE"), Duration.ofSeconds(30));
    assertThat(recordsFor(TOPIC + ".retry.1", "K-ONCE", 1)).hasSize(1);

    // Longer than the next retry delay: nothing more may follow.
    Thread.sleep(3_000);
    assertThat(handler.calls.get("K-ONCE")).hasValue(2);
    assertThat(recordsFor(TOPIC + ".retry.2", "K-ONCE", 1, Duration.ofSeconds(3))).isEmpty();
    assertThat(recordsFor(TOPIC + ".dlq", "K-ONCE", 1, Duration.ofSeconds(3))).isEmpty();
  }

  @Test
  void retryDelayDoesNotHoldTheConsumerThread() throws Exception {
    publisher.publish(
        TOPIC, "K-FREE", new EventMetadata("corr-c", "TXN-C", "idem-c", 1), rawJson("{\"case\":\"fail-once\"}"));
    await(() -> handler.calls.containsKey("K-FREE"), Duration.ofSeconds(30));

    // While retry.1 waits out its 2 s delay, no exchange is in flight on that route: the record
    // is held back by pausing its partition, not by a sleeping consumer thread.
    long until = System.nanoTime() + Duration.ofMillis(1_500).toNanos();
    while (System.nanoTime() < until && handler.calls.get("K-FREE").get() == 1) {
      assertThat(camel.getInflightRepository().size(TOPIC + ".retry.1.consume")).isZero();
      Thread.sleep(10);
    }
    await(() -> handler.succeeded.contains("K-FREE"), Duration.ofSeconds(30));
  }

  @Test
  void forgedFarFutureNotBeforeIsCappedAtTheConfiguredDelay() throws Exception {
    long start = System.currentTimeMillis();
    sendRaw(TOPIC + ".retry.1", "K-FORGED", Long.toString(start + Duration.ofHours(1).toMillis()));

    await(() -> handler.succeeded.contains("K-FORGED"), Duration.ofSeconds(30));
    assertThat(System.currentTimeMillis() - start).isLessThan(5_000);
  }

  @Test
  void garbageNotBeforeIsDueNowAndNotSentToDlq() throws Exception {
    long start = System.currentTimeMillis();
    sendRaw(TOPIC + ".retry.1", "K-GARBAGE", "not-a-number");

    await(() -> handler.succeeded.contains("K-GARBAGE"), Duration.ofSeconds(30));
    assertThat(System.currentTimeMillis() - start).as("sooner than the 2 s delay").isLessThan(2_000);
    assertThat(handler.calls.get("K-GARBAGE")).hasValue(1);
    assertThat(recordsFor(TOPIC + ".dlq", "K-GARBAGE", 1, Duration.ofSeconds(3))).isEmpty();
  }

  /** Writes a record straight onto a retry topic, as a misbehaving producer could. */
  void sendRaw(String topic, String key, String notBefore) throws Exception {
    Properties p = new Properties();
    p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
    p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    try (KafkaProducer<String, String> producer = new KafkaProducer<>(p)) {
      ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, "{\"case\":\"ok\"}");
      record.headers().add("mw-correlation-id", "corr-raw".getBytes(StandardCharsets.UTF_8));
      record.headers().add("mw-retry-attempt", "1".getBytes(StandardCharsets.UTF_8));
      record.headers().add("mw-retry-not-before", notBefore.getBytes(StandardCharsets.UTF_8));
      producer.send(record).get();
    }
  }

  /** Publishes a pre-built JSON body unchanged. */
  static tools.jackson.databind.JsonNode rawJson(String json) {
    return tools.jackson.databind.json.JsonMapper.builder().build().readTree(json);
  }
}
