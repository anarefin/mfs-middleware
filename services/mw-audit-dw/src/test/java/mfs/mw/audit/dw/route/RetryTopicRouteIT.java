package mfs.mw.audit.dw.route;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import mfs.mw.audit.dw.TestInfrastructure;
import mfs.mw.audit.dw.config.KafkaRetryProperties;
import mfs.mw.audit.dw.conformance.Conformance;
import mfs.mw.audit.dw.messaging.EventMetadata;
import mfs.mw.audit.dw.messaging.EventPublisher;
import mfs.mw.audit.dw.messaging.KafkaTestConsumer;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.component.kafka.KafkaConstants;
import org.apache.camel.spi.InflightRepository;
import org.apache.camel.test.spring.junit6.CamelSpringBootTest;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
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
@Conformance
@CamelSpringBootTest
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "mw.kafka.retry.delays=2s,2s,6s",
      // lets the test see how long an in-flight exchange has run (InflightRepository.oldest)
      "camel.main.inflight-repository-browse-enabled=true"
    })
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
    RetryTopicRouteBuilder itRoute(KafkaRetryProperties retry, Clock clock) {
      return new RetryTopicRouteBuilder(retry, clock) {
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

  private final CamelContext camel;
  private final EventPublisher publisher;
  private final KafkaContainer kafka;
  private final FlakyHandler handler;

  @Autowired
  RetryTopicRouteIT(CamelContext camel, EventPublisher publisher, KafkaContainer kafka, FlakyHandler handler) {
    this.camel = camel;
    this.publisher = publisher;
    this.kafka = kafka;
    this.handler = handler;
  }

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
    assertThat(header(parked, "mw-source")).isEqualTo("mw-audit-dw");
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

    // While retry.1 waits out its 2 s delay, no exchange stays in flight on that route: the record
    // is held back by pausing its partition, not by a sleeping consumer thread. Holding it back
    // is itself a short exchange (pause and seek, a few ms), so the check is on how long the
    // oldest in-flight exchange has run; a consumer sleeping out the delay would exceed the bound.
    long until = System.nanoTime() + Duration.ofMillis(1_500).toNanos();
    while (System.nanoTime() < until && handler.calls.get("K-FREE").get() == 1) {
      InflightRepository.InflightExchange oldest =
          camel.getInflightRepository().oldest(TOPIC + ".retry.1.consume");
      if (oldest != null) {
        assertThat(oldest.getDuration()).as("ms an exchange has been in flight on retry.1").isLessThan(500);
      }
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

  // Rebalance hold-back tests. The group's committed offset is read from the broker (AdminClient)
  // while the record is held back, across the forced rebalance and until it is handled: it must
  // never pass the held record, or a restart or new partition owner would skip it.

  @Test
  void heldBackRecordSurvivesARebalanceThatKeepsItsPartitionHere() throws Exception {
    // The new member's client id sorts after Camel's, so the range assignor gives the partition
    // back to the Camel consumer.
    long start = System.currentTimeMillis();
    HeldRecord held = sendRaw(TOPIC + ".retry.3", "K-KEPT", Long.toString(start + 6_000));
    Thread.sleep(1_000); // the retry.3 consumer has polled it and is holding it back
    assertNotCommittedPast(held, "K-KEPT", "while held back");

    boolean rebalanced = false;
    try (KafkaConsumer<String, String> member = groupMember("zzz-member")) {
      long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
      while (!handler.succeeded.contains("K-KEPT") && System.nanoTime() < deadline) {
        assertThat(member.poll(Duration.ofMillis(200))).as("the new member got no records").isEmpty();
        // Joining the group (a generation was assigned) forces every member to rebalance.
        rebalanced |= member.groupMetadata().generationId() > 0;
        assertNotCommittedPast(held, "K-KEPT", "across the rebalance");
      }
    }
    assertThat(rebalanced).as("a second member joined the group (forced rebalance)").isTrue();
    assertHandledOnceAfterDelay("K-KEPT", start, held);
  }

  @Test
  void heldBackRecordSurvivesItsPartitionMovingAwayAndBack() throws Exception {
    // The new member's client id sorts first: it takes the partition (and never commits), then
    // leaves, and the partition returns to the Camel consumer.
    long start = System.currentTimeMillis();
    HeldRecord held = sendRaw(TOPIC + ".retry.3", "K-MOVED", Long.toString(start + 6_000));
    Thread.sleep(1_000);
    assertNotCommittedPast(held, "K-MOVED", "while held back");

    boolean movedAway = false;
    try (KafkaConsumer<String, String> member = groupMember("aaa-member")) {
      long until = System.nanoTime() + Duration.ofSeconds(3).toNanos();
      while (System.nanoTime() < until) {
        member.poll(Duration.ofMillis(200));
        movedAway |= member.assignment().contains(held.partition());
        assertNotCommittedPast(held, "K-MOVED", "while the partition is away");
      }
    }
    assertThat(movedAway).as("the partition moved to the new member").isTrue();
    assertNotCommittedPast(held, "K-MOVED", "after the partition came back");
    assertHandledOnceAfterDelay("K-MOVED", start, held);
  }

  /** Where a record was written: its partition and offset. */
  record HeldRecord(TopicPartition partition, long offset) {}

  /** The retry.3 group's committed offset for the partition, from the broker; null if none. */
  Long committed(TopicPartition tp) throws Exception {
    try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()))) {
      OffsetAndMetadata o =
          admin.listConsumerGroupOffsets(RETRY_3_GROUP).partitionsToOffsetAndMetadata().get().get(tp);
      return o == null ? null : o.offset();
    }
  }

  /**
   * The committed offset (the next record to read) may pass the held record only once the
   * handler has succeeded on it. Checked in that order, read first: the handler records success
   * before the route commits, so a commit seen by the read implies success is already visible.
   * Checking success first and reading second would race the legitimate commit at the due time.
   */
  void assertNotCommittedPast(HeldRecord held, String key, String when) throws Exception {
    Long c = committed(held.partition());
    if (c != null && c > held.offset()) {
      assertThat(handler.succeeded)
          .as("committed offset %d %s passed the held record at %d before it was handled", c, when, held.offset())
          .contains(key);
    }
  }

  static final String RETRY_3_GROUP = "mw-audit-dw.retry.3";

  KafkaConsumer<String, String> groupMember(String clientId) {
    Properties p = new Properties();
    p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
    p.put(ConsumerConfig.GROUP_ID_CONFIG, RETRY_3_GROUP);
    p.put(ConsumerConfig.CLIENT_ID_CONFIG, clientId);
    p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
    p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    KafkaConsumer<String, String> member = new KafkaConsumer<>(p);
    member.subscribe(List.of(TOPIC + ".retry.3"));
    return member;
  }

  void assertHandledOnceAfterDelay(String key, long start, HeldRecord held) throws Exception {
    await(() -> handler.succeeded.contains(key), Duration.ofSeconds(60));
    assertThat(System.currentTimeMillis() - start).as("not before its delay").isGreaterThanOrEqualTo(5_500);
    Thread.sleep(3_000);
    assertThat(handler.calls.get(key)).as("handled exactly once").hasValue(1);
    assertThat(committed(held.partition())).as("committed once handled").isEqualTo(held.offset() + 1);
  }

  /** Writes a record straight onto a retry topic, as a misbehaving producer could. */
  HeldRecord sendRaw(String topic, String key, String notBefore) throws Exception {
    Properties p = new Properties();
    p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
    p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    try (KafkaProducer<String, String> producer = new KafkaProducer<>(p)) {
      ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, "{\"case\":\"ok\"}");
      record.headers().add("mw-correlation-id", "corr-raw".getBytes(StandardCharsets.UTF_8));
      record.headers().add("mw-retry-attempt", "1".getBytes(StandardCharsets.UTF_8));
      record.headers().add("mw-retry-not-before", notBefore.getBytes(StandardCharsets.UTF_8));
      RecordMetadata meta = producer.send(record).get();
      return new HeldRecord(new TopicPartition(meta.topic(), meta.partition()), meta.offset());
    }
  }

  /** Publishes a pre-built JSON body unchanged. */
  static tools.jackson.databind.JsonNode rawJson(String json) {
    return tools.jackson.databind.json.JsonMapper.builder().build().readTree(json);
  }
}
