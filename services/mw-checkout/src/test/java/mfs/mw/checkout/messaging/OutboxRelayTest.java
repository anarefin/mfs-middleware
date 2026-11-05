package mfs.mw.checkout.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import mfs.mw.checkout.config.OutboxProperties;
import mfs.mw.checkout.messaging.OutboxRelay.Outcome;
import org.junit.jupiter.api.Test;

/**
 * The relay's batching rules (spec 6.4), without Kafka or PostgreSQL: which acknowledged rows of
 * a batch may be marked sent, and when the relay polls again at once. {@code
 * OutboxRelayThroughputConformanceIT} and {@code OutboxRelayRecoveryConformanceIT} run the same
 * rules against real Kafka and PostgreSQL.
 */
class OutboxRelayTest {

  static Outcome acked(long id, String key) {
    return new Outcome(id, key, true);
  }

  static Outcome failed(long id, String key) {
    return new Outcome(id, key, false);
  }

  @Test
  void aFullyAcknowledgedBatchIsMarkedSentInIdOrder() {
    List<Outcome> batch = List.of(acked(1, "A"), acked(2, "B"), acked(3, "A"), acked(4, "C"));

    assertThat(OutboxRelay.markable(batch)).containsExactly(1L, 2L, 3L, 4L);
  }

  @Test
  void aFailedRowHoldsBackEveryLaterRowOfItsKeyEvenIfKafkaAckedThem() {
    List<Outcome> batch =
        List.of(acked(1, "A"), failed(2, "A"), acked(3, "A"), acked(4, "A"));

    // 3 and 4 reached Kafka, but marking them would let them overtake 2 on the next poll.
    assertThat(OutboxRelay.markable(batch)).containsExactly(1L);
  }

  @Test
  void aFailureForOneKeyDoesNotHoldBackOtherKeys() {
    List<Outcome> batch =
        List.of(
            acked(1, "A"),
            failed(2, "B"),
            acked(3, "A"),
            acked(4, "B"),
            acked(5, "C"),
            failed(6, "C"),
            acked(7, "C"));

    assertThat(OutboxRelay.markable(batch)).containsExactly(1L, 3L, 5L);
  }

  @Test
  void anEmptyBatchMarksNothing() {
    assertThat(OutboxRelay.markable(List.of())).isEmpty();
  }

  /** A relay whose batches are scripted, to see how often {@code relayPending} polls. */
  static final class ScriptedRelay extends OutboxRelay {
    final Deque<Integer> results;
    int polls;

    ScriptedRelay(int batchSize, Integer... results) {
      super(null, null, null, null, new OutboxProperties(true, Duration.ofMillis(500), batchSize), null);
      this.results = new ArrayDeque<>(List.of(results));
    }

    @Override
    public int relayBatch() {
      polls++;
      return results.isEmpty() ? 0 : results.poll();
    }
  }

  @Test
  void pollsAgainAtOnceWhileFullBatchesComeBackAndStopsOnAShortOne() {
    ScriptedRelay relay = new ScriptedRelay(500, 500, 500, 500, 120, 500);

    assertThat(relay.relayPending()).isEqualTo(1620);
    assertThat(relay.polls).isEqualTo(4);
  }

  @Test
  void anEmptyOutboxIsOnePoll() {
    ScriptedRelay relay = new ScriptedRelay(500);

    assertThat(relay.relayPending()).isZero();
    assertThat(relay.polls).isEqualTo(1);
  }
}
