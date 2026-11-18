package mfs.mw.simulators.messaging;

import static mfs.mw.simulators.messaging.KafkaTestTopics.dlqTopic;
import static mfs.mw.simulators.messaging.KafkaTestTopics.retryTopic;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.validation.ValidationException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import mfs.mw.simulators.TestInfrastructure;
import mfs.mw.simulators.conformance.Conformance;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.event.KeyValuePair;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.serializer.DeserializationException;

/**
 * Kafka consumer errors go to non-blocking retry topics, then the DLQ (spec 19.2, 6.2, ADR D25),
 * through Spring Kafka's retry topics ({@code config/KafkaConfig}) under the platform's names: a
 * record that keeps failing travels {@code <topic>.<group>.retry.1 -> .retry.2 -> .retry.3 ->
 * <topic>.<group>.dlq} with its key, body and 6.1 headers kept and the {@code mw-retry-*}
 * diagnostics added. Validation failures and records that cannot be deserialized go to the DLQ at
 * once. A retry delay pauses only its partition (never the consumer thread), a forged due time is
 * capped at the configured delay, and a held-back record is never committed past. Each record's
 * correlation and trace ids are in the MDC while it is handled, and only then.
 *
 * <p>Fast by design (no test waits 2 s, so none is {@code slow-wait}): the delays are shortened
 * here ({@code mw.kafka.retry.delays}; production 10 s, 1 min, 10 min), and the tests run one
 * after another on one context. The context has its own consumer group, so its retry and DLQ
 * topics are its own too.
 */
@Conformance
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "mw.kafka.retry.delays=" + RetryTopicIT.DELAYS,
      "spring.kafka.consumer.group-id=" + RetryTopicIT.GROUP
    })
@Import({TestInfrastructure.class, RetryTopicIT.Listeners.class})
class RetryTopicIT {

  static final String TOPIC = "it.retry.event.v1";
  /** Two partitions: a record held back on one must not hold up the other. */
  static final String FREE_TOPIC = "it.retry.free.v1";
  static final String POISON_TOPIC = "it.retry.poison.v1";
  static final String DISABLED_TOPIC = "it.retry.disabled.v1";
  static final String DELAYS = "500ms,100ms,100ms";
  static final long RETRY_1_MS = 500;
  static final long RETRY_2_MS = 100;
  /** Longer than any retry delay here: a wrong extra delivery or send would have shown up. */
  static final Duration SETTLE = Duration.ofMillis(500);
  /** This context's consumer group: the middle part of its retry and DLQ topic names. */
  static final String GROUP = "mw-simulators-retry-it";
  static final String SECRET_MSISDN = "01712345789";
  /** Spring Kafka's due-time header on a retry topic (big-endian epoch millis). */
  static final String BACKOFF_HEADER = "retry_topic-backoff-timestamp";

  /** What the listener saw in the MDC while it handled a record. */
  record Seen(String correlationId, String txnId, String traceId) {}

  /**
   * A test listener: "fail-always" always throws, "fail-once" throws on its first delivery,
   * "invalid" is a validation failure (not retryable), anything else succeeds.
   */
  public static class FlakyListener {
    final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
    final List<String> succeeded = new CopyOnWriteArrayList<>();
    final Map<String, Seen> mdc = new ConcurrentHashMap<>();

    @KafkaListener(
        id = "it-retry",
        topics = {TOPIC, FREE_TOPIC, POISON_TOPIC},
        groupId = "${spring.kafka.consumer.group-id}")
    public void on(ConsumerRecord<String, String> record, Acknowledgment ack) {
      String key = record.key();
      int call = calls.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
      mdc.put(key, new Seen(MDC.get("correlationId"), MDC.get("txnId"), MDC.get("traceId")));
      String body = record.value();
      if (body.contains("invalid")) {
        throw new ValidationException("schema check failed for " + body);
      }
      if (body.contains("fail-always") || (body.contains("fail-once") && call == 1)) {
        throw new IllegalStateException("handler failed for " + body);
      }
      succeeded.add(key);
      ack.acknowledge();
    }

    /** Switched off by its property, as a service switches its consumers off. */
    @KafkaListener(
        id = "it-disabled",
        topics = DISABLED_TOPIC,
        groupId = "${spring.kafka.consumer.group-id}",
        autoStartup = "${it.disabled.consumers.enabled:false}")
    public void disabled(ConsumerRecord<String, String> record, Acknowledgment ack) {
      ack.acknowledge();
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class Listeners {
    /**
     * The topics exist before the containers subscribe, as the platform declares them: retry
     * topics are never auto-created, and a consumer would only see a late topic at its next
     * metadata refresh.
     */
    @Bean
    FlakyListener flakyListener() {
      KafkaTestTopics.createWithRetries(TOPIC, GROUP, 3, 1);
      KafkaTestTopics.createWithRetries(FREE_TOPIC, GROUP, 3, 2);
      KafkaTestTopics.createWithRetries(POISON_TOPIC, GROUP, 3, 1);
      return new FlakyListener();
    }
  }

  private final EventPublisher publisher;
  private final FlakyListener listener;
  private final KafkaListenerEndpointRegistry registry;

  @Autowired
  RetryTopicIT(EventPublisher publisher, FlakyListener listener, KafkaListenerEndpointRegistry registry) {
    this.publisher = publisher;
    this.listener = listener;
    this.registry = registry;
  }

  // --- names and containers --------------------------------------------------------------

  /** Topic -> consumer group of every listener container in this context. */
  Map<String, String> groupsByTopic() {
    Map<String, String> out = new HashMap<>();
    for (MessageListenerContainer c : registry.getListenerContainers()) {
      for (String topic : c.getContainerProperties().getTopics()) {
        out.put(topic, c.getGroupId());
      }
    }
    return out;
  }

  List<MessageListenerContainer> containersOf(String topicPrefix) {
    List<MessageListenerContainer> out = new ArrayList<>();
    for (MessageListenerContainer c : registry.getListenerContainers()) {
      for (String topic : c.getContainerProperties().getTopics()) {
        if (topic.startsWith(topicPrefix)) {
          out.add(c);
          break;
        }
      }
    }
    return out;
  }

  @Test
  void retryAndDlqTopicsAndGroupsCarryTheServiceGroup() {
    assertThat(groupsByTopic())
        .containsEntry("it.retry.event.v1", "mw-simulators-retry-it")
        .containsEntry("it.retry.event.v1.mw-simulators-retry-it.retry.1", "mw-simulators-retry-it.retry.1")
        .containsEntry("it.retry.event.v1.mw-simulators-retry-it.retry.2", "mw-simulators-retry-it.retry.2")
        .containsEntry("it.retry.event.v1.mw-simulators-retry-it.retry.3", "mw-simulators-retry-it.retry.3")
        .containsKey("it.retry.event.v1.mw-simulators-retry-it.dlq")
        .doesNotContainKey("it.retry.event.v1.mw-simulators-retry-it.retry.4");
  }

  @Test
  void theServiceNeverConsumesItsOwnDlq() {
    List<MessageListenerContainer> dlq =
        registry.getListenerContainers().stream()
            .filter(c -> c.getContainerProperties().getTopics() != null)
            .filter(c -> List.of(c.getContainerProperties().getTopics()).stream().anyMatch(t -> t.endsWith(".dlq")))
            .toList();
    assertThat(dlq).isNotEmpty().noneMatch(MessageListenerContainer::isRunning);
  }

  @Test
  void aSwitchedOffListenerStartsNeitherItsMainNorItsRetryConsumers() {
    List<MessageListenerContainer> disabled = containersOf(DISABLED_TOPIC);
    assertThat(disabled).hasSizeGreaterThanOrEqualTo(4).noneMatch(MessageListenerContainer::isRunning);
    assertThat(containersOf(TOPIC + ".")).anyMatch(MessageListenerContainer::isRunning);
  }

  // --- the retry path -------------------------------------------------------------------

  @Test
  void failingRecordGoesThroughEachRetryTopicThenDlqWithHeadersKept() {
    // Every log event of the context during the hops, read at the source.
    Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    ListAppender<ILoggingEvent> logged = new ListAppender<>();
    logged.start();
    root.addAppender(logged);
    try {
      failingRecordTravelsToTheDlq();
    } finally {
      root.detachAppender(logged);
    }

    // Retry and DLQ logging never shows the payload or the exception message (18.3).
    List<ILoggingEvent> events = List.copyOf(logged.list);
    assertThat(events).anySatisfy(e -> assertThat(text(e)).contains("event=kafka.dlq.parked"));
    assertThat(events).anySatisfy(e -> assertThat(text(e)).contains("event=kafka.retry.scheduled"));
    assertThat(events).allSatisfy(e -> assertThat(text(e)).doesNotContain(SECRET_MSISDN));
  }

  void failingRecordTravelsToTheDlq() {
    String body = "{\"schemaVersion\":1,\"case\":\"fail-always\",\"msisdn\":\"" + SECRET_MSISDN + "\"}";
    publisher.publish(TOPIC, "K-ALWAYS", new EventMetadata("corr-a", "TXN-A", "idem-a", 1), rawJson(body));

    List<ConsumerRecord<String, String>> dlq = recordsFor(dlqTopic(TOPIC, GROUP), "K-ALWAYS", 1);
    assertThat(dlq).hasSize(1);

    ConsumerRecord<String, String> previous = null;
    for (int n = 1; n <= 3; n++) {
      List<ConsumerRecord<String, String>> retry = recordsFor(retryTopic(TOPIC, GROUP, n), "K-ALWAYS", 1);
      assertThat(retry).as("record on retry.%d", n).hasSize(1);
      ConsumerRecord<String, String> r = retry.getFirst();
      assertThat(header(r, "mw-retry-attempt")).isEqualTo(Integer.toString(n));
      assertThat(header(r, "mw-original-topic")).isEqualTo(TOPIC);
      assertThat(Long.parseLong(header(r, "mw-retry-not-before"))).isGreaterThan(r.timestamp());
      assertThat(r.value()).isEqualTo(body);
      if (previous != null) {
        assertThat(r.timestamp() - previous.timestamp())
            .as("retry.%d waits for its delay", n)
            .isGreaterThanOrEqualTo(n == 2 ? RETRY_1_MS : RETRY_2_MS);
      }
      previous = r;
    }

    ConsumerRecord<String, String> parked = dlq.getFirst();
    assertThat(parked.key()).isEqualTo("K-ALWAYS");
    assertThat(parked.value()).isEqualTo(body);
    assertThat(header(parked, "mw-correlation-id")).isEqualTo("corr-a");
    assertThat(header(parked, "mw-txn-id")).isEqualTo("TXN-A");
    assertThat(header(parked, "mw-idempotency-key")).isEqualTo("idem-a");
    assertThat(header(parked, "mw-source")).isEqualTo("mw-simulators");
    assertThat(header(parked, "mw-schema-version")).isEqualTo("1");
    assertThat(header(parked, "traceparent")).isNotBlank();
    assertThat(header(parked, "mw-retry-attempt")).isEqualTo("4");
    assertThat(header(parked, "mw-original-topic")).isEqualTo(TOPIC);
    assertThat(header(parked, "mw-error-class")).isEqualTo(IllegalStateException.class.getName());
    assertThat(header(parked, "mw-retry-not-before")).isNull();
    // One value per diagnostic header, and never the exception message (it may carry payload data).
    assertThat(parked.headers().headers("mw-retry-attempt")).hasSize(1);
    assertThat(KafkaTestConsumer.headers(parked).values()).noneMatch(v -> v != null && v.contains(SECRET_MSISDN));
    assertThat(listener.calls.get("K-ALWAYS")).hasValue(4);
  }

  @Test
  void recordThatSucceedsOnRetryIsNotSentToDlq() throws Exception {
    publisher.publish(
        TOPIC, "K-ONCE", new EventMetadata("corr-b", "TXN-B", "idem-b", 1), rawJson("{\"case\":\"fail-once\"}"));

    await(() -> listener.succeeded.contains("K-ONCE"), Duration.ofSeconds(30));
    assertThat(recordsFor(retryTopic(TOPIC, GROUP, 1), "K-ONCE", 1)).hasSize(1);

    Thread.sleep(SETTLE.toMillis());
    assertThat(listener.calls.get("K-ONCE")).hasValue(2);
    assertThat(recordsOn(retryTopic(TOPIC, GROUP, 2), "K-ONCE")).isEmpty();
    assertThat(recordsOn(dlqTopic(TOPIC, GROUP), "K-ONCE")).isEmpty();
  }

  @Test
  void validationFailureGoesToTheDlqAtOnce() {
    publisher.publish(
        TOPIC, "K-INVALID", new EventMetadata("corr-v", "TXN-V", "idem-v", 1), rawJson("{\"case\":\"invalid\"}"));

    List<ConsumerRecord<String, String>> dlq = recordsFor(dlqTopic(TOPIC, GROUP), "K-INVALID", 1);
    assertThat(dlq).hasSize(1);
    assertThat(header(dlq.getFirst(), "mw-retry-attempt")).isEqualTo("1");
    assertThat(header(dlq.getFirst(), "mw-error-class")).isEqualTo(ValidationException.class.getName());
    assertThat(recordsOn(retryTopic(TOPIC, GROUP, 1), "K-INVALID")).isEmpty();
    assertThat(listener.calls.get("K-INVALID")).hasValue(1);
  }

  @Test
  void recordThatCannotBeDeserializedGoesToTheDlqAtOnceAndTheNextRecordIsConsumed() throws Exception {
    byte[] notUtf8 = {'{', (byte) 0xC3, (byte) 0x28, '}'};
    sendRaw(POISON_TOPIC, 0, "K-POISON", notUtf8, Map.of());
    sendRaw(POISON_TOPIC, 0, "K-AFTER", bytes("{\"case\":\"ok\"}"), Map.of());

    await(() -> listener.succeeded.contains("K-AFTER"), Duration.ofSeconds(30));
    List<ConsumerRecord<String, String>> dlq = recordsFor(dlqTopic(POISON_TOPIC, GROUP), "K-POISON", 1);
    assertThat(dlq).hasSize(1);
    assertThat(header(dlq.getFirst(), "mw-error-class")).isEqualTo(DeserializationException.class.getName());
    assertThat(header(dlq.getFirst(), "mw-retry-attempt")).isEqualTo("1");
    assertThat(recordsOn(retryTopic(POISON_TOPIC, GROUP, 1), "K-POISON")).isEmpty();
    assertThat(listener.calls).doesNotContainKey("K-POISON");
  }

  // --- holding back a record on a retry topic --------------------------------------------

  @Test
  void aHeldBackRecordPausesOnlyItsPartitionAndIsNotCommittedPast() throws Exception {
    long start = System.currentTimeMillis();
    String retry1 = retryTopic(FREE_TOPIC, GROUP, 1);
    HeldRecord held = sendRaw(retry1, 0, "K-HELD", bytes("{\"case\":\"ok\"}"), backoff(start + RETRY_1_MS));
    sendRaw(retry1, 1, "K-DUE", bytes("{\"case\":\"ok\"}"), backoff(start));

    // The due record on the other partition is handled while the first one waits: the consumer
    // thread keeps polling, it does not sleep out the delay.
    await(() -> listener.succeeded.contains("K-DUE"), Duration.ofSeconds(30));
    assertThat(listener.succeeded).doesNotContain("K-HELD");
    assertNotCommittedPast(held, "K-HELD");

    await(() -> listener.succeeded.contains("K-HELD"), Duration.ofSeconds(30));
    assertThat(System.currentTimeMillis() - start).as("not before its delay").isGreaterThanOrEqualTo(RETRY_1_MS - 100);
    assertThat(listener.calls.get("K-HELD")).as("handled once").hasValue(1);
  }

  @Test
  void forgedFarFutureDueTimeIsCappedAtTheConfiguredDelay() throws Exception {
    long start = System.currentTimeMillis();
    sendRaw(retryTopic(FREE_TOPIC, GROUP, 1), 0, "K-FORGED", bytes("{\"case\":\"ok\"}"),
        backoff(start + Duration.ofHours(1).toMillis()));

    await(() -> listener.succeeded.contains("K-FORGED"), Duration.ofSeconds(30));
    assertThat(System.currentTimeMillis() - start).isLessThan(5_000);
  }

  @Test
  void aFarFutureDueTimeLongerThanALongIsDueNow() throws Exception {
    long start = System.currentTimeMillis();
    byte[] far = BigInteger.valueOf(start + Duration.ofHours(1).toMillis()).toByteArray();
    byte[] nine = new byte[Long.BYTES + 1]; // leading zero bytes: the same number to a lenient reader
    System.arraycopy(far, 0, nine, nine.length - far.length, far.length);
    sendRaw(retryTopic(FREE_TOPIC, GROUP, 1), 1, "K-NINE", bytes("{\"case\":\"ok\"}"), Map.of(BACKOFF_HEADER, nine));

    await(() -> listener.succeeded.contains("K-NINE"), Duration.ofSeconds(30));
    assertThat(System.currentTimeMillis() - start).as("sooner than the retry.1 delay").isLessThan(RETRY_1_MS);
  }

  @Test
  void garbageDueTimeIsDueNowAndNotSentToDlq() throws Exception {
    long start = System.currentTimeMillis();
    sendRaw(retryTopic(FREE_TOPIC, GROUP, 1), 1, "K-GARBAGE", bytes("{\"case\":\"ok\"}"),
        Map.of(BACKOFF_HEADER, bytes("not-a-number")));

    await(() -> listener.succeeded.contains("K-GARBAGE"), Duration.ofSeconds(30));
    assertThat(System.currentTimeMillis() - start).as("sooner than the retry.1 delay").isLessThan(RETRY_1_MS);
    assertThat(listener.calls.get("K-GARBAGE")).hasValue(1);
    assertThat(recordsOn(dlqTopic(FREE_TOPIC, GROUP), "K-GARBAGE")).isEmpty();
  }

  // --- consumer-side context -------------------------------------------------------------

  @Test
  void correlationAndTraceIdsAreInTheMdcWhileARecordIsHandledAndOnlyThen() throws Exception {
    publisher.publish(
        TOPIC, "K-MDC", new EventMetadata("corr-mdc", "TXN-MDC", "idem-mdc", 1), rawJson("{\"case\":\"ok\"}"));
    await(() -> listener.succeeded.contains("K-MDC"), Duration.ofSeconds(30));
    String traceparent = header(recordsFor(TOPIC, "K-MDC", 1).getFirst(), "traceparent");

    assertThat(listener.mdc.get("K-MDC"))
        .isEqualTo(new Seen("corr-mdc", "TXN-MDC", traceparent.split("-")[1]));

    // The next record carries no ids: nothing is left over from the previous one.
    sendRaw(TOPIC, 0, "K-MDC-NONE", bytes("{\"case\":\"ok\"}"), Map.of());
    await(() -> listener.succeeded.contains("K-MDC-NONE"), Duration.ofSeconds(30));
    Seen none = listener.mdc.get("K-MDC-NONE");
    assertThat(none.correlationId()).isNull();
    assertThat(none.txnId()).isNull();
    assertThat(none.traceId()).isNotEqualTo(traceparent.split("-")[1]);
  }

  // --- helpers ---------------------------------------------------------------------------

  /** Where a record was written: its partition and offset. */
  record HeldRecord(TopicPartition partition, long offset) {}

  /** The retry.1 group's committed offset must not pass the held record before it is handled. */
  void assertNotCommittedPast(HeldRecord held, String key) throws Exception {
    try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, TestInfrastructure.kafkaBootstrapServers()))) {
      OffsetAndMetadata o =
          admin.listConsumerGroupOffsets(GROUP + ".retry.1").partitionsToOffsetAndMetadata().get().get(held.partition());
      if (o != null && o.offset() > held.offset()) {
        assertThat(listener.succeeded)
            .as("committed offset %d passed the held record at %d before it was handled", o.offset(), held.offset())
            .contains(key);
      }
    }
  }

  static Map<String, byte[]> backoff(long dueAtMillis) {
    return Map.of(BACKOFF_HEADER, BigInteger.valueOf(dueAtMillis).toByteArray());
  }

  static byte[] bytes(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  /** Writes a record straight onto a topic, as a misbehaving producer could. */
  HeldRecord sendRaw(String topic, int partition, String key, byte[] value, Map<String, byte[]> headers)
      throws Exception {
    Properties p = new Properties();
    p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, TestInfrastructure.kafkaBootstrapServers());
    p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
    try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(p)) {
      ProducerRecord<String, byte[]> record = new ProducerRecord<>(topic, partition, key, value);
      headers.forEach((name, v) -> record.headers().add(name, v));
      RecordMetadata meta = producer.send(record).get();
      return new HeldRecord(new TopicPartition(meta.topic(), meta.partition()), meta.offset());
    }
  }

  List<ConsumerRecord<String, String>> recordsFor(String topic, String key, int count) {
    return KafkaTestConsumer.read(
        TestInfrastructure.kafkaBootstrapServers(), topic, count, Duration.ofSeconds(30), r -> key.equals(r.key()));
  }

  /** Every record already on {@code topic} with {@code key}; returns without waiting. */
  List<ConsumerRecord<String, String>> recordsOn(String topic, String key) {
    return KafkaTestConsumer.readToEnd(TestInfrastructure.kafkaBootstrapServers(), topic, r -> key.equals(r.key()));
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
      Thread.sleep(20);
    }
  }

  /** A log event as text: message, key/value pairs and any exception message. */
  static String text(ILoggingEvent e) {
    StringBuilder b = new StringBuilder(e.getFormattedMessage());
    List<KeyValuePair> kvs = e.getKeyValuePairs();
    if (kvs != null) {
      for (KeyValuePair kv : kvs) {
        b.append(' ').append(kv.key).append('=').append(kv.value);
      }
    }
    if (e.getThrowableProxy() != null) {
      b.append(' ').append(e.getThrowableProxy().getMessage());
    }
    return b.toString();
  }

  /** Publishes a pre-built JSON body unchanged. */
  static tools.jackson.databind.JsonNode rawJson(String json) {
    return tools.jackson.databind.json.JsonMapper.builder().build().readTree(json);
  }
}
