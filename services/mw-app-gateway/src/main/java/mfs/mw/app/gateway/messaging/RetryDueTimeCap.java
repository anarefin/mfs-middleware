package mfs.mw.app.gateway.messaging;

import java.math.BigInteger;
import java.time.Clock;
import mfs.mw.app.gateway.config.KafkaRetryProperties;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.kafka.retrytopic.RetryTopicHeaders;

/**
 * Caps the due time of a record on a retry topic at its record timestamp plus that retry's
 * configured delay, before Spring Kafka reads it (spec 19.2). Spring Kafka holds a retry record
 * back (pausing its partition, never sleeping on the consumer thread) until the time in its {@code
 * retry_topic-backoff-timestamp} header, and only ignores values more than a day ahead: a record
 * written onto a retry topic with a forged far-future time would stall its partition. Here the
 * header is rewritten to {@code min(header, min(timestamp, now) + delay(n))}. A header that is not
 * a long (empty, or longer than 8 bytes) is removed, so the record is due now: never left for a
 * reader that might decode it differently.
 *
 * <p>Runs as a {@link RecordInterceptor} on the consumer thread, before the listener adapter;
 * records of other topics pass unchanged.
 */
public final class RetryDueTimeCap implements RecordInterceptor<Object, Object> {

  private static final String DUE = RetryTopicHeaders.DEFAULT_HEADER_BACKOFF_TIMESTAMP;

  private final KafkaRetryProperties retry;
  private final Clock clock;

  public RetryDueTimeCap(KafkaRetryProperties retry, Clock clock) {
    this.retry = retry;
    this.clock = clock;
  }

  @Override
  public ConsumerRecord<Object, Object> intercept(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
    int n = RetryTopicNaming.retryNumber(record.topic());
    if (n < 1 || n > retry.attempts()) {
      return record;
    }
    Header due = record.headers().lastHeader(DUE);
    if (due == null) {
      return record;
    }
    if (due.value() == null || due.value().length == 0 || due.value().length > Long.BYTES) {
      // Not a long: Spring Kafka 4.1 ignores such a value, but no reader gets the chance to
      // decode it differently from us. Removed, the record is due now.
      record.headers().remove(DUE);
      return record;
    }
    long now = clock.millis();
    long written = Math.min(record.timestamp(), now);
    long cap = written + retry.delay(n).toMillis();
    long dueAt = new BigInteger(due.value()).longValue();
    if (dueAt > cap) {
      record.headers().remove(DUE);
      record.headers().add(DUE, BigInteger.valueOf(cap).toByteArray());
    }
    return record;
  }
}
