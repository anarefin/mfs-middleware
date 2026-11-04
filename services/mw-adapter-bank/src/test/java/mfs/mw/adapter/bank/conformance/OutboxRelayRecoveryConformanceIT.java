package mfs.mw.adapter.bank.conformance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import mfs.mw.adapter.bank.TestInfrastructure;
import mfs.mw.adapter.bank.config.KafkaRetryProperties;
import mfs.mw.adapter.bank.config.OutboxProperties;
import mfs.mw.adapter.bank.messaging.EventMetadata;
import mfs.mw.adapter.bank.messaging.EventPublisher;
import mfs.mw.adapter.bank.messaging.Inbox;
import mfs.mw.adapter.bank.messaging.KafkaTestConsumer;
import mfs.mw.adapter.bank.messaging.OutboxRelay;
import mfs.mw.adapter.bank.messaging.OutboxWriter;
import mfs.mw.adapter.bank.route.RetryTopicRouteBuilder;
import org.apache.camel.Exchange;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * The outbox relay after a crash, a restart and with two instances (spec 6.4, 19.3). The relay
 * route is off here (test default), so each test drives {@link OutboxRelay} itself: a relay that
 * dies after Kafka acknowledged a record but before its transaction committed leaves the row
 * unsent; the relay of the restarted service publishes it again, and the consumer's inbox
 * applies each event once. Two relays running at once never publish a row twice.
 */
@Conformance
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import({TestInfrastructure.class, OutboxRelayRecoveryConformanceIT.Routes.class})
class OutboxRelayRecoveryConformanceIT {

  static final String TOPIC = "conformance.outbox.recovery.v1";
  static final String CONSUMER = "conformance-recovery";

  /** The process dying between Kafka's ack and the relay's commit. */
  static final class SimulatedCrash extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }

  static class EffectHandler {
    final Inbox inbox;
    final JdbcTemplate jdbc;
    final Map<String, AtomicInteger> deliveries = new ConcurrentHashMap<>();

    EffectHandler(Inbox inbox, JdbcTemplate jdbc) {
      this.inbox = inbox;
      this.jdbc = jdbc;
    }

    public void handle(Exchange exchange) {
      String eventId =
          JsonMapper.builder().build().readTree(exchange.getMessage().getBody(String.class)).get("eventId").asString();
      deliveries.computeIfAbsent(eventId, k -> new AtomicInteger()).incrementAndGet();
      inbox.processOnce(
          CONSUMER,
          eventId,
          () -> jdbc.update("insert into conformance_recovery_effect (event_id) values (?)", eventId));
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class Routes {
    @Bean
    EffectHandler effectHandler(Inbox inbox, JdbcTemplate jdbc) {
      return new EffectHandler(inbox, jdbc);
    }

    @Bean
    RetryTopicRouteBuilder recoveryRoute(KafkaRetryProperties retry, Clock clock) {
      return new RetryTopicRouteBuilder(retry, clock) {
        @Override
        protected String topic() {
          return TOPIC;
        }

        @Override
        protected String handlerUri() {
          return "bean:effectHandler?method=handle";
        }
      };
    }
  }

  private final OutboxWriter outbox;
  private final OutboxRelay relay;
  private final EventPublisher publisher;
  private final OutboxProperties properties;
  private final JsonMapper json;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final KafkaContainer kafka;
  private final EffectHandler handler;

  @Autowired
  OutboxRelayRecoveryConformanceIT(OutboxWriter outbox, OutboxRelay relay, EventPublisher publisher, OutboxProperties properties, JsonMapper json, JdbcTemplate jdbc, TransactionTemplate tx, KafkaContainer kafka, EffectHandler handler) {
    this.outbox = outbox;
    this.relay = relay;
    this.publisher = publisher;
    this.properties = properties;
    this.json = json;
    this.jdbc = jdbc;
    this.tx = tx;
    this.kafka = kafka;
    this.handler = handler;
  }

  @BeforeAll
  static void effectTable(@Autowired JdbcTemplate jdbc) {
    jdbc.execute("create table if not exists conformance_recovery_effect (event_id text not null)");
  }

  @BeforeEach
  void noUnsentRowsOfOtherTests() {
    relay.relayPending();
  }

  void append(String eventId) {
    tx.executeWithoutResult(
        s ->
            outbox.append(
                TOPIC,
                "TXN-" + eventId,
                new EventMetadata("corr-" + eventId, "TXN-" + eventId, "idem-" + eventId, 1),
                Map.of("schemaVersion", 1, "eventId", eventId)));
  }

  /** A relay instance whose process "dies" right after Kafka acknowledged its first record. */
  OutboxRelay crashingRelay() {
    return new OutboxRelay(jdbc, tx, publisher, json, properties) {
      @Override
      protected void markSent(long id) {
        throw new SimulatedCrash();
      }
    };
  }

  int unsent(String prefix) {
    return jdbc.queryForObject(
        "select count(*) from outbox where sent_at is null and msg_key like ?", Integer.class, "TXN-" + prefix + "%");
  }

  List<ConsumerRecord<String, String>> published(String prefix, int atLeast, Duration timeout) {
    return KafkaTestConsumer.read(
        kafka.getBootstrapServers(), TOPIC, atLeast, timeout, r -> r.key().startsWith("TXN-" + prefix));
  }

  int effects(String eventId) {
    return jdbc.queryForObject(
        "select count(*) from conformance_recovery_effect where event_id = ?", Integer.class, eventId);
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
  void pendingRowsAreRelayedAfterACrashAndRestartWithNoDuplicatePastTheInbox() throws Exception {
    List<String> events = List.of("crash-1", "crash-2", "crash-3");
    events.forEach(this::append);

    // Crash: crash-1 reached Kafka, but the relay died before marking it sent.
    assertThatThrownBy(() -> crashingRelay().relayBatch()).isInstanceOf(SimulatedCrash.class);
    assertThat(unsent("crash-")).as("nothing marked sent by the crashed relay").isEqualTo(3);
    assertThat(published("crash-", 1, Duration.ofSeconds(10))).hasSize(1);

    // Restart: a fresh relay publishes every pending row, crash-1 for the second time.
    assertThat(relay.relayPending()).isGreaterThanOrEqualTo(3);
    assertThat(unsent("crash-")).isZero();
    List<ConsumerRecord<String, String>> onKafka = published("crash-", 4, Duration.ofSeconds(20));
    assertThat(onKafka).extracting(ConsumerRecord::key)
        .containsExactlyInAnyOrder("TXN-crash-1", "TXN-crash-1", "TXN-crash-2", "TXN-crash-3");

    // The consumer saw crash-1 twice and applied it once.
    await(() -> handler.deliveries.getOrDefault("crash-1", new AtomicInteger()).get() == 2
        && events.stream().allMatch(e -> handler.deliveries.containsKey(e)), Duration.ofSeconds(30));
    for (String e : events) {
      assertThat(effects(e)).as("side effect of %s", e).isEqualTo(1);
    }

    // Nothing is left to relay: a later run publishes nothing more.
    relay.relayPending();
    assertThat(published("crash-", 5, Duration.ofSeconds(3))).hasSize(4);
  }

  @Test
  void twoRelaysRunningAtOnceNeverPublishARowTwice() throws Exception {
    int rows = 60;
    for (int i = 0; i < rows; i++) {
      append("multi-" + i);
    }

    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      CountDownLatch start = new CountDownLatch(1);
      List<Callable<Integer>> instances = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        instances.add(
            () -> {
              start.await();
              int sent = 0;
              while (unsent("multi-") > 0) {
                sent += relay.relayBatch();
              }
              return sent;
            });
      }
      List<Future<Integer>> results = new ArrayList<>();
      for (Callable<Integer> c : instances) {
        results.add(pool.submit(c));
      }
      start.countDown();
      int total = 0;
      for (Future<Integer> f : results) {
        total += f.get();
      }
      assertThat(total).isEqualTo(rows);
    } finally {
      pool.shutdownNow();
    }

    List<ConsumerRecord<String, String>> onKafka = published("multi-", rows + 1, Duration.ofSeconds(10));
    assertThat(onKafka).hasSize(rows);
    assertThat(onKafka).extracting(ConsumerRecord::key).doesNotHaveDuplicates();
  }
}
