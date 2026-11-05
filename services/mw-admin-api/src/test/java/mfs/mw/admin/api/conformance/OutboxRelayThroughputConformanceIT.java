package mfs.mw.admin.api.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import mfs.mw.admin.api.TestInfrastructure;
import mfs.mw.admin.api.messaging.EventMetadata;
import mfs.mw.admin.api.messaging.KafkaTestConsumer;
import mfs.mw.admin.api.messaging.OutboxRelay;
import mfs.mw.admin.api.messaging.OutboxWriter;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * The batched, pipelined outbox relay against real Kafka and PostgreSQL (spec 6.4): a backlog
 * drains well above 1,000 rows/s with every key's events in id order on Kafka, and a row Kafka
 * refuses holds back the later rows of its key (not other keys) so none is marked sent ahead of
 * it. The relay route is off (test default); the tests drive {@link OutboxRelay} themselves.
 */
@Conformance
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestInfrastructure.class)
class OutboxRelayThroughputConformanceIT {

  private static final Logger log = LoggerFactory.getLogger(OutboxRelayThroughputConformanceIT.class);

  static final String TOPIC = "conformance.outbox.throughput.v1";
  static final int PARTITIONS = 6;
  static final int ROWS = 10_000;
  static final int KEYS = 50;

  private final OutboxWriter outbox;
  private final OutboxRelay relay;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;

  @Autowired
  OutboxRelayThroughputConformanceIT(
      OutboxWriter outbox, OutboxRelay relay, JdbcTemplate jdbc, TransactionTemplate tx) {
    this.outbox = outbox;
    this.relay = relay;
    this.jdbc = jdbc;
    this.tx = tx;
  }

  /** Several partitions, so keys spread over them as in the platform topics. */
  @BeforeAll
  static void topic() throws Exception {
    Properties p = new Properties();
    p.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, TestInfrastructure.kafkaBootstrapServers());
    try (Admin admin = Admin.create(p)) {
      if (!admin.listTopics().names().get().contains(TOPIC)) {
        admin.createTopics(List.of(new NewTopic(TOPIC, PARTITIONS, (short) 1))).all().get();
      }
    }
  }

  @BeforeEach
  void noUnsentRowsOfOtherTests() {
    relay.relayPending();
  }

  /** A row Kafka refuses must not stay behind for other tests' relays. */
  @AfterEach
  void dropRefusedRows() {
    jdbc.update("delete from outbox where sent_at is null and msg_key like 'TXN-poison-%'");
  }

  void append(String key, int seq, Object payload) {
    outbox.append(
        TOPIC,
        key,
        new EventMetadata("corr-" + key, key, "idem-" + key + "-" + seq, 1),
        Map.of("schemaVersion", 1, "eventId", key + "-" + seq, "seq", seq, "data", payload));
  }

  int unsent(String keyPrefix) {
    return jdbc.queryForObject(
        "select count(*) from outbox where sent_at is null and msg_key like ?", Integer.class, keyPrefix + "%");
  }

  List<ConsumerRecord<String, String>> published(String keyPrefix, int atLeast, Duration timeout) {
    return KafkaTestConsumer.read(
        TestInfrastructure.kafkaBootstrapServers(), TOPIC, atLeast, timeout, r -> r.key().startsWith(keyPrefix));
  }

  static int seq(ConsumerRecord<String, String> r) {
    return JsonMapper.builder().build().readTree(r.value()).get("seq").asInt();
  }

  @Test
  void aBacklogDrainsAboveAThousandRowsPerSecondInIdOrderPerKey() {
    tx.executeWithoutResult(
        s -> {
          for (int i = 0; i < ROWS; i++) {
            append("TXN-load-" + (i % KEYS), i, "x");
          }
        });

    long start = System.nanoTime();
    int sent = relay.relayPending();
    double seconds = (System.nanoTime() - start) / 1e9;
    double rate = ROWS / seconds;
    log.atInfo()
        .addKeyValue("event", "outbox.relay.throughput")
        .addKeyValue("rows", ROWS)
        .addKeyValue("durationMs", (long) (seconds * 1000))
        .addKeyValue("rowsPerSecond", (long) rate)
        .log("outbox relay drained the backlog");

    assertThat(sent).isEqualTo(ROWS);
    assertThat(unsent("TXN-load-")).isZero();
    assertThat(rate).as("rows/s").isGreaterThan(1_000);

    List<ConsumerRecord<String, String>> onKafka = published("TXN-load-", ROWS, Duration.ofSeconds(60));
    assertThat(onKafka).hasSize(ROWS);
    Map<String, List<Integer>> perKey = new LinkedHashMap<>();
    for (ConsumerRecord<String, String> r : onKafka) {
      perKey.computeIfAbsent(r.key(), k -> new ArrayList<>()).add(seq(r));
    }
    assertThat(perKey).hasSize(KEYS);
    perKey.forEach((key, seqs) -> assertThat(seqs).as("order of %s", key).isSorted().doesNotHaveDuplicates());
  }

  @Test
  void aRefusedRowHoldsBackTheLaterRowsOfItsKeyButNoOtherKey() {
    String tooLarge = "x".repeat(2 * 1024 * 1024); // above the producer's max.request.size
    tx.executeWithoutResult(
        s -> {
          append("TXN-poison-A", 1, "ok");
          append("TXN-poison-B", 1, "ok");
          append("TXN-poison-A", 2, tooLarge);
          append("TXN-poison-B", 2, "ok");
          append("TXN-poison-A", 3, "ok");
          append("TXN-poison-B", 3, "ok");
        });

    assertThat(relay.relayBatch()).isEqualTo(4);

    // A-1 and every B row are sent; A-2 (refused) and A-3 (acknowledged, but after A-2) are not.
    assertThat(
            jdbc.queryForList(
                "select payload->>'seq' from outbox where sent_at is null and msg_key = 'TXN-poison-A' order by id",
                String.class))
        .containsExactly("2", "3");
    assertThat(unsent("TXN-poison-B")).isZero();
    List<ConsumerRecord<String, String>> b = published("TXN-poison-B", 3, Duration.ofSeconds(20));
    assertThat(b).extracting(OutboxRelayThroughputConformanceIT::seq).containsExactly(1, 2, 3);
  }
}
