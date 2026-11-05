package mfs.mw.adapter.billpay.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.camel.Message;
import org.apache.camel.component.kafka.KafkaConstants;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultMessage;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Holding back a retry record when Kafka drops the partition's pause but the consumer keeps the
 * partition (a rebalance that recreates its state): the record is fetched again and must be
 * paused and rewound again, not skipped because of a stale entry from before the rebalance.
 */
class RetryDelayListenerTest {

  static final TopicPartition TP = new TopicPartition("it.event.v1.retry.1", 0);
  static final long HELD_OFFSET = 5;

  final AtomicLong now = new AtomicLong(1_000_000);
  final Clock clock =
      new Clock() {
        @Override
        public long millis() {
          return now.get();
        }

        @Override
        public Instant instant() {
          return Instant.ofEpochMilli(now.get());
        }

        @Override
        public java.time.ZoneId getZone() {
          return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
          return this;
        }
      };

  MockConsumer<String, String> consumer;
  RetryDelayListener listener;

  @BeforeEach
  void setUp() {
    consumer = new MockConsumer<>("earliest");
    consumer.assign(List.of(TP));
    consumer.updateBeginningOffsets(Map.of(TP, 0L));
    consumer.seek(TP, HELD_OFFSET + 1); // the poll returned the held record and moved past it
    listener = new RetryDelayListener(Duration.ofSeconds(10), clock);
    listener.setConsumer(consumer);
  }

  Message heldRecord() {
    Message msg = new DefaultMessage(new DefaultCamelContext());
    msg.setHeader(KafkaConstants.TOPIC, TP.topic());
    msg.setHeader(KafkaConstants.PARTITION, TP.partition());
    msg.setHeader(KafkaConstants.OFFSET, HELD_OFFSET);
    msg.setHeader(KafkaConstants.TIMESTAMP, now.get());
    msg.setHeader(MwHeaders.RETRY_NOT_BEFORE, Long.toString(now.get() + 10_000));
    return msg;
  }

  @Test
  void recordNotDueIsHeldByPausingAndRewindingItsPartition() {
    assertThat(listener.holdBack(heldRecord())).isTrue();
    assertThat(consumer.paused()).containsExactly(TP);
    assertThat(consumer.position(TP)).isEqualTo(HELD_OFFSET);
  }

  @Test
  void refetchedAfterThePauseWasDroppedItIsPausedAndRewoundAgain() {
    Message msg = heldRecord();
    assertThat(listener.holdBack(msg)).isTrue();

    // Rebalance keeps the partition but recreates its state: unpaused, fetched again.
    consumer.resume(Set.of(TP));
    consumer.seek(TP, HELD_OFFSET + 1);

    assertThat(listener.holdBack(msg)).as("still not due").isTrue();
    assertThat(consumer.paused()).as("paused again, not skipped").containsExactly(TP);
    assertThat(consumer.position(TP)).as("rewound to the held record").isEqualTo(HELD_OFFSET);
  }

  @Test
  void recordWithoutKafkaCoordinatesIsHandledNotHeld() {
    Message msg = heldRecord();
    msg.removeHeader(KafkaConstants.OFFSET);
    assertThat(listener.holdBack(msg)).as("cannot be rewound, so never paused").isFalse();
    msg.removeHeader(KafkaConstants.PARTITION);
    assertThat(listener.holdBack(msg)).isFalse();
    assertThat(consumer.paused()).isEmpty();
  }

  @Test
  void dueRecordIsResumedAndHandled() {
    Message msg = heldRecord();
    assertThat(listener.holdBack(msg)).isTrue();

    now.addAndGet(10_000);
    listener.afterConsume(null);

    assertThat(consumer.paused()).isEmpty();
    assertThat(listener.holdBack(msg)).isFalse();
  }
}
