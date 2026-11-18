package mfs.mw.portal.api.messaging;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.function.BiFunction;
import java.util.function.Function;
import mfs.mw.portal.api.config.KafkaRetryProperties;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.SeekUtils;
import org.springframework.kafka.listener.TimestampedException;
import org.springframework.kafka.retrytopic.RetryTopicHeaders;

/**
 * Parks a failed record on its next retry topic or on the DLQ (spec 19.2, 6.2, ADR D25). Spring
 * Kafka's retry-topic machinery decides where it goes and when it is due; this recoverer, plugged
 * in by {@code config/KafkaConfig}, adds what the platform reads on top (mw-admin-api's DLQ view
 * and replay):
 *
 * <ul>
 *   <li>{@code mw-retry-attempt}: failed deliveries so far ({@code n} on retry {@code n}; on the
 *       DLQ every failed delivery);
 *   <li>{@code mw-original-topic}: the topic the record was first consumed from;
 *   <li>{@code mw-error-class}: the class of the listener's exception;
 *   <li>{@code mw-retry-not-before}: the injected clock plus the delay of the retry it goes to;
 *       never on the DLQ.
 * </ul>
 *
 * <p>Key, body and every other header (the 6.1 headers, {@code traceparent}) are kept unchanged.
 * Each {@code mw-retry-*} header is replaced, not repeated, on every hop. The exception message and
 * stack trace are never written, logged or forwarded (18.3): they may carry payload data. The log
 * shows topic, attempt, correlation/txn ids and the exception class only.
 */
public final class RetryTopicRecoverer extends DeadLetterPublishingRecoverer {

  private static final Logger log = LoggerFactory.getLogger(RetryTopicRecoverer.class);

  private final KafkaRetryProperties retry;
  private final Clock clock;

  public RetryTopicRecoverer(
      Function<ProducerRecord<?, ?>, ? extends KafkaOperations<?, ?>> templateResolver,
      BiFunction<ConsumerRecord<?, ?>, Exception, TopicPartition> destinationResolver,
      KafkaRetryProperties retry,
      Clock clock) {
    super(templateResolver, destinationResolver);
    this.retry = retry;
    this.clock = clock;
    excludeHeader(HeaderNames.HeadersToAdd.EX_MSG, HeaderNames.HeadersToAdd.EX_STACKTRACE);
  }

  /**
   * Stamps {@code mw-error-class} (the class of the listener's own exception, below Spring
   * Kafka's wrappers) on the failed record, whose headers the parked copy starts from, then parks
   * it. Done here rather than in a recoverer-factory headers function: in Spring Kafka 4.1 adding
   * one logs a spurious "Replacing custom headers function" warning per listener container.
   */
  @Override
  public void accept(ConsumerRecord<?, ?> record, Consumer<?, ?> consumer, Exception exception) {
    if (!SeekUtils.isBackoffException(exception)) { // a record held back for its delay did not fail
      replace(record.headers(), MwHeaders.ERROR_CLASS, errorClass(exception));
    }
    super.accept(record, consumer, exception);
  }

  /** The listener's exception class, unwrapping Spring Kafka's listener and timestamp wrappers. */
  static String errorClass(Throwable exception) {
    Throwable t = exception;
    while ((t instanceof ListenerExecutionFailedException || t instanceof TimestampedException)
        && t.getCause() != null) {
      t = t.getCause();
    }
    return t.getClass().getName();
  }

  @Override
  protected ProducerRecord<Object, Object> createProducerRecord(
      ConsumerRecord<?, ?> record, TopicPartition topicPartition, Headers headers, byte[] key, byte[] value) {
    int retryNumber = RetryTopicNaming.retryNumber(topicPartition.topic());
    boolean dlq = retryNumber == 0;
    // Spring Kafka's attempt counter on the outgoing record counts the delivery to come, so the
    // failed deliveries so far are one less.
    int failures = springAttempts(headers) - 1;
    String originalTopic = text(record.headers().lastHeader(MwHeaders.ORIGINAL_TOPIC));
    if (originalTopic == null) {
      originalTopic = record.topic();
    }
    replace(headers, MwHeaders.RETRY_ATTEMPT, Integer.toString(failures));
    replace(headers, MwHeaders.ORIGINAL_TOPIC, originalTopic);
    if (dlq) {
      headers.remove(MwHeaders.RETRY_NOT_BEFORE);
    } else {
      replace(headers, MwHeaders.RETRY_NOT_BEFORE, Long.toString(clock.millis() + retry.delay(retryNumber).toMillis()));
    }
    log.atWarn()
        .addKeyValue("event", dlq ? "kafka.dlq.parked" : "kafka.retry.scheduled")
        .addKeyValue("topic", originalTopic)
        .addKeyValue("attempt", failures)
        .addKeyValue("correlationId", text(headers.lastHeader(MwHeaders.CORRELATION_ID)))
        .addKeyValue("txnId", text(headers.lastHeader(MwHeaders.TXN_ID)))
        .addKeyValue("errorClass", text(headers.lastHeader(MwHeaders.ERROR_CLASS)))
        .log(dlq ? "record moved to DLQ" : "record moved to retry topic");
    return super.createProducerRecord(record, topicPartition, headers, key, value);
  }

  /** Spring Kafka's {@code retry_topic-attempts} (a 4-byte int); 2 when absent (first failure). */
  private static int springAttempts(Headers headers) {
    Header h = headers.lastHeader(RetryTopicHeaders.DEFAULT_HEADER_ATTEMPTS);
    if (h == null || h.value() == null || h.value().length != Integer.BYTES) {
      return 2;
    }
    return ByteBuffer.wrap(h.value()).getInt();
  }

  private static void replace(Headers headers, String name, String value) {
    headers.remove(name);
    headers.add(name, utf8(value));
  }

  private static String text(Header header) {
    return MwHeaders.text(header);
  }

  private static byte[] utf8(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }
}
