package mfs.mw.simulators.route;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import mfs.mw.simulators.TestInfrastructure;
import mfs.mw.simulators.config.KafkaRetryProperties;
import mfs.mw.simulators.conformance.Conformance;
import mfs.mw.simulators.messaging.EventMetadata;
import mfs.mw.simulators.messaging.EventPublisher;
import mfs.mw.simulators.messaging.KafkaTestConsumer;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.component.kafka.KafkaConstants;
import org.apache.camel.spi.InflightRepository;
import org.apache.camel.test.spring.junit6.CamelSpringBootTest;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValuePair;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Kafka consumer errors go to non-blocking retry topics, then the DLQ (spec 19.2, 6.2): a record
 * that keeps failing travels {@code <topic>.<group>.retry.1 -> .retry.2 -> .retry.3 -> .dlq} with
 * its key and 6.1 headers kept (the topics carry this service's consumer group, so services sharing
 * a source topic never share parked records); a record that succeeds on a retry never reaches the DLQ.
 * Holding a record back never blocks the consumer thread, and a forged {@code mw-retry-not-before}
 * cannot stall it.
 *
 * <p>Fast by design (ticket 53, under 10 s): the delays are configurable ({@code mw.kafka.retry.delays},
 * production 10 s, 1 min, 10 min) and shortened here, consumers heartbeat every 500 ms so a rebalance
 * takes about a second, and three independent routes (own source, retry and DLQ topics) run their
 * tests side by side: {@link #TOPIC} the retry path, {@link #FREE_TOPIC} the hold-back checks on
 * retry.1, {@link #HOLD_TOPIC} the rebalance tests, whose retry.3 delay is long enough to move its
 * partition while a record is still held back. The methods run side by side ({@code @Execution} on
 * each method, not the class: the class itself must not overlap other test classes and their
 * contexts, which reset logging). Tests that hold back a record on a route never overlap
 * ({@link ResourceLock}); the two retry-path tests share {@link #TOPIC}, told apart by their keys.
 */
@Conformance
@CamelSpringBootTest
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "mw.kafka.retry.delays=" + RetryTopicRouteIT.DELAYS,
      "camel.component.kafka.group-id=" + RetryTopicRouteIT.GROUP,
      // a forced rebalance reaches every member at its next heartbeat (default 3 s)
      "camel.component.kafka.heartbeat-interval-ms=500",
      // lets the test see how long an in-flight exchange has run (InflightRepository.oldest)
      "camel.main.inflight-repository-browse-enabled=true"
    })
@Import({TestInfrastructure.class, RetryTopicRouteIT.Routes.class})
class RetryTopicRouteIT {

  static final String TOPIC = "it.retry.event.v1";
  static final String FREE_TOPIC = "it.retry.free.v1";
  static final String HOLD_TOPIC = "it.retry.hold.v1";
  static final String DELAYS = "1s,200ms,200ms";
  static final long RETRY_1_MS = 1_000;
  static final long RETRY_2_MS = 200;
  /** retry.3 delay of the {@link #HOLD_TOPIC} route: outlasts the forced rebalance. */
  static final long HOLD_MS = 2_000;
  /** Long enough past a due time that a wrong extra delivery or send would have shown up (2+ polls). */
  static final Duration SETTLE = Duration.ofMillis(1_000);
  /** How long an "is nothing there" read waits for a record that must not exist. */
  static final Duration ABSENT = Duration.ofMillis(300);
  /**
   * The consumer group of this test's routes, part of their retry and DLQ topic names; in production
   * it is the service name. Own to this context, so a forced rebalance of {@code <group>.retry.3}
   * involves only this test's fast-heartbeat consumers, not idle ones of other cached contexts.
   */
  static final String GROUP = "mw-simulators-retry-it";
  static final String DLQ = TOPIC + "." + GROUP + ".dlq";
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

    /**
     * The topics exist before the route's consumers subscribe, as the platform declares them
     * ({@code deploy/kafka/topics.txt}). Left to auto-creation on first send, a consumer would
     * only see a new retry topic at its next metadata refresh, and how long a test waited would
     * depend on which test happened to park a record first.
     */
    @Bean
    RetryTopicRouteBuilder itRoute(KafkaRetryProperties retry, Clock clock, KafkaContainer kafka)
        throws Exception {
      createTopics(kafka);
      return route(TOPIC, retry, clock);
    }

    @Bean
    RetryTopicRouteBuilder itFreeRoute(KafkaRetryProperties retry, Clock clock) {
      return route(FREE_TOPIC, retry, clock);
    }

    @Bean
    RetryTopicRouteBuilder itHoldRoute(Clock clock) {
      KafkaRetryProperties hold =
          new KafkaRetryProperties(List.of(Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofMillis(HOLD_MS)));
      return route(HOLD_TOPIC, hold, clock);
    }

    static RetryTopicRouteBuilder route(String topic, KafkaRetryProperties retry, Clock clock) {
      return new RetryTopicRouteBuilder(retry, clock) {
        @Override
        protected String topic() {
          return topic;
        }

        @Override
        protected String handlerUri() {
          return "bean:flakyHandler?method=handle";
        }
      };
    }
  }

  static void createTopics(KafkaContainer kafka) throws Exception {
    List<NewTopic> topics =
        List.of(TOPIC, FREE_TOPIC, HOLD_TOPIC).stream()
            .flatMap(t -> List.of(t, retryTopic(t, 1), retryTopic(t, 2), retryTopic(t, 3), dlq(t)).stream())
            .map(name -> new NewTopic(name, 1, (short) 1))
            .toList();
    try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()))) {
      for (NewTopic topic : topics) {
        try {
          admin.createTopics(List.of(topic)).all().get();
        } catch (ExecutionException e) {
          if (!(e.getCause() instanceof TopicExistsException)) {
            throw e;
          }
        }
      }
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

  /** Every record already on {@code topic} with {@code key} (waits {@link #ABSENT} for late ones). */
  List<ConsumerRecord<String, String>> recordsOn(String topic, String key) {
    return recordsFor(topic, key, Integer.MAX_VALUE, ABSENT);
  }

  /** {@code <topic>.<group>.retry.<n>}, the name the route parks on (B10). */
  static String retryTopic(int n) {
    return retryTopic(TOPIC, n);
  }

  static String retryTopic(String topic, int n) {
    return topic + "." + GROUP + ".retry." + n;
  }

  static String dlq(String topic) {
    return topic + "." + GROUP + ".dlq";
  }

  @Test
  @Execution(ExecutionMode.CONCURRENT)
  void retryAndDlqTopicNamesCarryTheConsumerGroup() {
    assertThat(RetryTopicRouteBuilder.retryTopic(TOPIC, GROUP, 2)).isEqualTo(retryTopic(2));
    assertThat(RetryTopicRouteBuilder.dlqTopic(TOPIC, GROUP)).isEqualTo(DLQ);
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

  @Tag("slow-wait")
  @Test
  @Execution(ExecutionMode.CONCURRENT)
  @ResourceLock("it.retry.event")
  void failingRecordGoesThroughEachRetryTopicThenDlqWithHeadersKept() {
    // The route's log events are read at the source, not from captured stdout: tests run side by
    // side here, and a stdout capture is one per JVM.
    Logger routeLog = (Logger) LoggerFactory.getLogger(RetryTopicRouteBuilder.class);
    ListAppender<ILoggingEvent> logged = new ListAppender<>();
    logged.start();
    routeLog.addAppender(logged);
    try {
      failingRecordTravelsToTheDlq();
    } finally {
      routeLog.detachAppender(logged);
    }

    // Retry and DLQ logging never shows the payload or the exception message (18.3).
    List<ILoggingEvent> events = List.copyOf(logged.list);
    assertThat(events).anySatisfy(e -> assertThat(text(e)).contains("event=kafka.dlq.parked"));
    assertThat(events).allSatisfy(e -> assertThat(text(e)).doesNotContain(SECRET_MSISDN));
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

  void failingRecordTravelsToTheDlq() {
    String body = "{\"schemaVersion\":1,\"case\":\"fail-always\",\"msisdn\":\"" + SECRET_MSISDN + "\"}";
    publisher.publish(TOPIC, "K-ALWAYS", new EventMetadata("corr-a", "TXN-A", "idem-a", 1), rawJson(body));

    List<ConsumerRecord<String, String>> dlq = recordsFor(DLQ, "K-ALWAYS", 1);
    assertThat(dlq).hasSize(1);

    ConsumerRecord<String, String> previous = null;
    for (int n = 1; n <= 3; n++) {
      List<ConsumerRecord<String, String>> retry = recordsFor(retryTopic(n), "K-ALWAYS", 1);
      assertThat(retry).as("record on retry.%d", n).hasSize(1);
      ConsumerRecord<String, String> r = retry.getFirst();
      assertThat(header(r, "mw-retry-attempt")).isEqualTo(Integer.toString(n));
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
    assertThat(handler.calls.get("K-ALWAYS")).hasValue(4);
  }

  @Tag("slow-wait")
  @Test
  @Execution(ExecutionMode.CONCURRENT)
  void recordThatSucceedsOnRetryIsNotSentToDlq() throws Exception {
    publisher.publish(
        TOPIC, "K-ONCE", new EventMetadata("corr-b", "TXN-B", "idem-b", 1), rawJson("{\"case\":\"fail-once\"}"));

    await(() -> handler.succeeded.contains("K-ONCE"), Duration.ofSeconds(30));
    assertThat(recordsFor(retryTopic(1), "K-ONCE", 1)).hasSize(1);

    // Longer than the next retry delay: nothing more may follow.
    Thread.sleep(SETTLE.toMillis());
    assertThat(handler.calls.get("K-ONCE")).hasValue(2);
    assertThat(recordsOn(retryTopic(2), "K-ONCE")).isEmpty();
    assertThat(recordsOn(DLQ, "K-ONCE")).isEmpty();
  }

  @Test
  @Execution(ExecutionMode.CONCURRENT)
  @ResourceLock("it.retry.free")
  void retryDelayDoesNotHoldTheConsumerThread() throws Exception {
    publisher.publish(
        FREE_TOPIC, "K-FREE", new EventMetadata("corr-c", "TXN-C", "idem-c", 1), rawJson("{\"case\":\"fail-once\"}"));
    await(() -> handler.calls.containsKey("K-FREE"), Duration.ofSeconds(30));

    // While retry.1 waits out its 1 s delay, no exchange stays in flight on that route: the record
    // is held back by pausing its partition, not by a sleeping consumer thread. Holding it back
    // is itself a short exchange (pause and seek, a few ms), so the check is on how long the
    // oldest in-flight exchange has run; a consumer sleeping out the delay would exceed the bound.
    long until = System.nanoTime() + Duration.ofMillis(1_500).toNanos();
    while (System.nanoTime() < until && handler.calls.get("K-FREE").get() == 1) {
      InflightRepository.InflightExchange oldest =
          camel.getInflightRepository().oldest(FREE_TOPIC + ".retry.1.consume");
      if (oldest != null) {
        assertThat(oldest.getDuration()).as("ms an exchange has been in flight on retry.1").isLessThan(500);
      }
      Thread.sleep(10);
    }
    await(() -> handler.succeeded.contains("K-FREE"), Duration.ofSeconds(30));
  }

  @Test
  @Execution(ExecutionMode.CONCURRENT)
  @ResourceLock("it.retry.free")
  void forgedFarFutureNotBeforeIsCappedAtTheConfiguredDelay() throws Exception {
    long start = System.currentTimeMillis();
    sendRaw(retryTopic(FREE_TOPIC, 1), "K-FORGED", Long.toString(start + Duration.ofHours(1).toMillis()));

    await(() -> handler.succeeded.contains("K-FORGED"), Duration.ofSeconds(30));
    assertThat(System.currentTimeMillis() - start).isLessThan(5_000);
  }

  @Test
  @Execution(ExecutionMode.CONCURRENT)
  @ResourceLock("it.retry.free")
  void garbageNotBeforeIsDueNowAndNotSentToDlq() throws Exception {
    long start = System.currentTimeMillis();
    sendRaw(retryTopic(FREE_TOPIC, 1), "K-GARBAGE", "not-a-number");

    await(() -> handler.succeeded.contains("K-GARBAGE"), Duration.ofSeconds(30));
    assertThat(System.currentTimeMillis() - start).as("sooner than the 1 s delay").isLessThan(RETRY_1_MS);
    assertThat(handler.calls.get("K-GARBAGE")).hasValue(1);
    assertThat(recordsOn(dlq(FREE_TOPIC), "K-GARBAGE")).isEmpty();
  }

  // Rebalance hold-back tests. The group's committed offset is read from the broker (AdminClient)
  // while the record is held back, across the forced rebalance and until it is handled: it must
  // never pass the held record, or a restart or new partition owner would skip it.

  @Tag("slow-wait")
  @Test
  @Execution(ExecutionMode.CONCURRENT)
  @ResourceLock("it.retry.hold")
  void heldBackRecordSurvivesARebalanceThatKeepsItsPartitionHere() throws Exception {
    // The new member's client id sorts after Camel's, so the range assignor gives the partition
    // back to the Camel consumer.
    long start = System.currentTimeMillis();
    HeldRecord held = sendRaw(retryTopic(HOLD_TOPIC, 3), "K-KEPT", Long.toString(start + HOLD_MS));
    Thread.sleep(500); // the retry.3 consumer has polled it and is holding it back
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

  @Tag("slow-wait")
  @Test
  @Execution(ExecutionMode.CONCURRENT)
  @ResourceLock("it.retry.hold")
  void heldBackRecordSurvivesItsPartitionMovingAwayAndBack() throws Exception {
    // The new member's client id sorts first: it takes the partition (and never commits), then
    // leaves, and the partition returns to the Camel consumer. The member polls until it really
    // holds the partition: how long the rebalance takes depends on when the Camel member's next
    // heartbeat (every 500 ms here) tells it to rejoin, so a fixed window was a race (D25).
    long start = System.currentTimeMillis();
    HeldRecord held = sendRaw(retryTopic(HOLD_TOPIC, 3), "K-MOVED", Long.toString(start + HOLD_MS));
    Thread.sleep(500);
    assertNotCommittedPast(held, "K-MOVED", "while held back");

    boolean movedAway = false;
    try (KafkaConsumer<String, String> member = groupMember("aaa-member")) {
      long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
      while (!movedAway && System.nanoTime() < deadline) {
        member.poll(Duration.ofMillis(200));
        movedAway = member.assignment().contains(held.partition());
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

  static final String RETRY_3_GROUP = GROUP + ".retry.3";

  KafkaConsumer<String, String> groupMember(String clientId) {
    Properties p = new Properties();
    p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
    p.put(ConsumerConfig.GROUP_ID_CONFIG, RETRY_3_GROUP);
    p.put(ConsumerConfig.CLIENT_ID_CONFIG, clientId);
    p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
    p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    KafkaConsumer<String, String> member = new KafkaConsumer<>(p);
    member.subscribe(List.of(retryTopic(HOLD_TOPIC, 3)));
    return member;
  }

  void assertHandledOnceAfterDelay(String key, long start, HeldRecord held) throws Exception {
    await(() -> handler.succeeded.contains(key), Duration.ofSeconds(60));
    assertThat(System.currentTimeMillis() - start).as("not before its delay").isGreaterThanOrEqualTo(HOLD_MS - 500);
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (!Long.valueOf(held.offset() + 1).equals(committed(held.partition())) && System.nanoTime() < deadline) {
      Thread.sleep(100);
    }
    assertThat(committed(held.partition())).as("committed once handled").isEqualTo(held.offset() + 1);
    // A redelivery (a lost hold-back or a seek back past the commit) would follow within a poll.
    Thread.sleep(SETTLE.toMillis());
    assertThat(handler.calls.get(key)).as("handled exactly once").hasValue(1);
    assertThat(committed(held.partition())).as("still committed once").isEqualTo(held.offset() + 1);
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
