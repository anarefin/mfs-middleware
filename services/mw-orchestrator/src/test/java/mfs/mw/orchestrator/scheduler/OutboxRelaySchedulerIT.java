package mfs.mw.orchestrator.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import mfs.mw.orchestrator.TestInfrastructure;
import mfs.mw.orchestrator.messaging.EventMetadata;
import mfs.mw.orchestrator.messaging.KafkaTestConsumer;
import mfs.mw.orchestrator.messaging.OutboxWriter;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The outbox relay runs on its own schedule ({@code mw.outbox.relay.period}, spec 6.4): rows
 * committed at any time are published without anyone calling the relay, the first right after
 * start-up and every later one within a few periods. The context is closed afterwards, so its
 * relay cannot pick up rows of other tests (the relay is off by default in tests).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {"mw.outbox.relay.enabled=true", "mw.outbox.relay.period=50ms"})
@Import(TestInfrastructure.class)
@DirtiesContext
class OutboxRelaySchedulerIT {

  static final String TOPIC = "it.outbox.scheduler.v1";

  private final OutboxWriter outbox;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;

  @Autowired
  OutboxRelaySchedulerIT(OutboxWriter outbox, JdbcTemplate jdbc, TransactionTemplate tx) {
    this.outbox = outbox;
    this.jdbc = jdbc;
    this.tx = tx;
  }

  void append(String key) {
    tx.executeWithoutResult(
        s -> outbox.append(TOPIC, key, new EventMetadata("corr-" + key, key, "idem-" + key, 1), Map.of("k", key)));
  }

  List<ConsumerRecord<String, String>> published(String key) {
    return KafkaTestConsumer.read(
        TestInfrastructure.kafkaBootstrapServers(), TOPIC, 1, Duration.ofSeconds(20), r -> key.equals(r.key()));
  }

  int unsent() {
    return jdbc.queryForObject("select count(*) from outbox where sent_at is null", Integer.class);
  }

  @Test
  void rowsCommittedOneAfterAnotherAreEachRelayedByTheSchedule() throws Exception {
    append("TXN-SCHED-1");
    assertThat(published("TXN-SCHED-1")).hasSize(1);

    // A later commit, after the relay found the outbox empty, is picked up by a later run.
    append("TXN-SCHED-2");
    assertThat(published("TXN-SCHED-2")).hasSize(1);

    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (unsent() > 0 && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }
    assertThat(unsent()).isZero();
  }
}
