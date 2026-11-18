package mfs.mw.adapter.billpay.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import mfs.mw.adapter.billpay.config.KafkaRetryProperties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.junit.jupiter.api.Test;

/**
 * The due time Spring Kafka reads ({@code retry_topic-backoff-timestamp}) never lies past the
 * record timestamp plus that retry's delay, whatever bytes a producer wrote: a value past the cap
 * is lowered to it, and one Spring Kafka might read differently from us (empty, or longer than a
 * long) is removed, so the record is due now. Records of other topics are not touched.
 */
class RetryDueTimeCapTest {

  static final Instant NOW = Instant.parse("2026-11-18T10:00:00Z");
  static final String HEADER = "retry_topic-backoff-timestamp";
  static final String RETRY_1 = "it.event.v1.mw-x.retry.1";

  final RetryDueTimeCap cap =
      new RetryDueTimeCap(
          new KafkaRetryProperties(List.of(Duration.ofSeconds(10), Duration.ofMinutes(1)), List.of()),
          Clock.fixed(NOW, ZoneOffset.UTC));

  static ConsumerRecord<Object, Object> record(String topic, long timestamp, byte[] due) {
    RecordHeaders headers = new RecordHeaders();
    if (due != null) {
      headers.add(HEADER, due);
    }
    return new ConsumerRecord<>(
        topic, 0, 5L, timestamp, TimestampType.CREATE_TIME, -1, -1, "k", "{}", headers, Optional.empty());
  }

  static byte[] millis(long value) {
    return BigInteger.valueOf(value).toByteArray();
  }

  static Long due(ConsumerRecord<Object, Object> r) {
    Header h = r.headers().lastHeader(HEADER);
    return h == null ? null : new BigInteger(h.value()).longValue();
  }

  @Test
  void aDueTimeWithinTheDelayIsKept() {
    long ts = NOW.toEpochMilli();
    ConsumerRecord<Object, Object> r = cap.intercept(record(RETRY_1, ts, millis(ts + 5_000)), null);
    assertThat(due(r)).isEqualTo(ts + 5_000);
  }

  @Test
  void aFarFutureDueTimeIsLoweredToTimestampPlusThatRetrysDelay() {
    long ts = NOW.toEpochMilli() - 1_000;
    ConsumerRecord<Object, Object> r =
        cap.intercept(record("it.event.v1.mw-x.retry.2", ts, millis(ts + Duration.ofHours(1).toMillis())), null);
    assertThat(due(r)).isEqualTo(ts + 60_000);
    assertThat(r.headers().headers(HEADER)).hasSize(1);
  }

  @Test
  void aDueTimeLongerThanALongIsRemovedSoTheRecordIsDueNow() {
    // 9 bytes: leading zero bytes in front of a far-future time, which a lenient reader would
    // still take as that time.
    byte[] far = millis(NOW.toEpochMilli() + Duration.ofHours(1).toMillis());
    byte[] nine = new byte[Long.BYTES + 1];
    System.arraycopy(far, 0, nine, nine.length - far.length, far.length);
    ConsumerRecord<Object, Object> r = cap.intercept(record(RETRY_1, NOW.toEpochMilli(), nine), null);
    assertThat(r.headers().lastHeader(HEADER)).isNull();
  }

  @Test
  void anEmptyDueTimeIsRemoved() {
    ConsumerRecord<Object, Object> r = cap.intercept(record(RETRY_1, NOW.toEpochMilli(), new byte[0]), null);
    assertThat(r.headers().lastHeader(HEADER)).isNull();
  }

  @Test
  void recordsOfOtherTopicsAreNotTouched() {
    long far = NOW.toEpochMilli() + Duration.ofHours(1).toMillis();
    ConsumerRecord<Object, Object> r = cap.intercept(record("it.event.v1", NOW.toEpochMilli(), millis(far)), null);
    assertThat(due(r)).isEqualTo(far);
  }
}
