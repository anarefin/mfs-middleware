package mfs.mw.ussd.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import mfs.mw.ussd.TestInfrastructure;
import mfs.mw.ussd.config.KafkaRetryProperties;
import mfs.mw.ussd.messaging.EventMetadata;
import mfs.mw.ussd.messaging.EventPublisher;
import mfs.mw.ussd.messaging.Inbox;
import mfs.mw.ussd.route.RetryTopicRouteBuilder;
import org.apache.camel.Exchange;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Inbox (spec 6.4, 19.3): a consumer records each applied {@code eventId} in the same transaction
 * as its side effect, so an event delivered again is a no-op, and a side effect that fails
 * leaves no record, so the retry still applies it.
 */
@Conformance
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = "mw.kafka.retry.delays=1s,1s,1s")
@Import({TestInfrastructure.class, InboxConformanceIT.Routes.class})
class InboxConformanceIT {

  static final String TOPIC = "conformance.inbox.event.v1";
  static final String CONSUMER = "conformance-inbox";

  /** A consumer whose side effect is not idempotent by itself: it appends a row per call. */
  static class CountingHandler {
    final Inbox inbox;
    final JdbcTemplate jdbc;
    final Map<String, AtomicInteger> deliveries = new ConcurrentHashMap<>();

    CountingHandler(Inbox inbox, JdbcTemplate jdbc) {
      this.inbox = inbox;
      this.jdbc = jdbc;
    }

    public void handle(Exchange exchange) {
      String eventId =
          JsonMapper.builder().build().readTree(exchange.getMessage().getBody(String.class)).get("eventId").asString();
      int delivery = deliveries.computeIfAbsent(eventId, k -> new AtomicInteger()).incrementAndGet();
      inbox.processOnce(
          CONSUMER,
          eventId,
          () -> {
            jdbc.update("insert into conformance_effect (event_id) values (?)", eventId);
            if (eventId.startsWith("fail-first") && delivery == 1) {
              throw new IllegalStateException("side effect failed after its write");
            }
          });
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class Routes {
    @Bean
    CountingHandler countingHandler(Inbox inbox, JdbcTemplate jdbc) {
      return new CountingHandler(inbox, jdbc);
    }

    @Bean
    RetryTopicRouteBuilder inboxRoute(KafkaRetryProperties retry, Clock clock) {
      return new RetryTopicRouteBuilder(retry, clock) {
        @Override
        protected String topic() {
          return TOPIC;
        }

        @Override
        protected String handlerUri() {
          return "bean:countingHandler?method=handle";
        }
      };
    }
  }

  private final EventPublisher publisher;
  private final CountingHandler handler;
  private final JdbcTemplate jdbc;

  @Autowired
  InboxConformanceIT(EventPublisher publisher, CountingHandler handler, JdbcTemplate jdbc) {
    this.publisher = publisher;
    this.handler = handler;
    this.jdbc = jdbc;
  }

  @BeforeAll
  static void effectTable(@Autowired JdbcTemplate jdbc) {
    jdbc.execute("create table if not exists conformance_effect (event_id text not null)");
  }

  void deliver(String eventId) {
    publisher.publish(
        TOPIC,
        "TXN-" + eventId,
        new EventMetadata("corr-" + eventId, "TXN-" + eventId, "idem-" + eventId, 1),
        Map.of("schemaVersion", 1, "eventId", eventId));
  }

  int effects(String eventId) {
    return jdbc.queryForObject(
        "select count(*) from conformance_effect where event_id = ?", Integer.class, eventId);
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

  int deliveries(String eventId) {
    AtomicInteger n = handler.deliveries.get(eventId);
    return n == null ? 0 : n.get();
  }

  @Test
  void sameEventIdDeliveredTwiceHasItsSideEffectAppliedOnce() throws Exception {
    deliver("evt-dup-1");
    deliver("evt-dup-1");

    await(() -> deliveries("evt-dup-1") == 2, Duration.ofSeconds(30));
    assertThat(effects("evt-dup-1")).isEqualTo(1);
  }

  @Test
  void failedSideEffectLeavesNoInboxRecordSoTheRetryAppliesIt() throws Exception {
    deliver("fail-first-1");

    await(() -> deliveries("fail-first-1") == 2, Duration.ofSeconds(30));
    await(() -> effects("fail-first-1") == 1, Duration.ofSeconds(5));
    Thread.sleep(1_500);
    assertThat(effects("fail-first-1")).isEqualTo(1);
    assertThat(deliveries("fail-first-1")).isEqualTo(2);
  }

  @Test
  void differentConsumersOfTheSameEventEachApplyItOnce() {
    assertThat(handler.inbox.processOnce("consumer-a", "evt-shared", () -> {})).isTrue();
    assertThat(handler.inbox.processOnce("consumer-b", "evt-shared", () -> {})).isTrue();
    assertThat(handler.inbox.processOnce("consumer-a", "evt-shared", () -> {})).isFalse();
  }
}
