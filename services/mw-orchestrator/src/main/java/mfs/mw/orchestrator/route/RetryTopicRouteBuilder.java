package mfs.mw.orchestrator.route;

import java.time.Clock;
import mfs.mw.orchestrator.config.KafkaRetryProperties;
import mfs.mw.orchestrator.messaging.MwHeaders;
import mfs.mw.orchestrator.messaging.RetryDelayListener;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.kafka.KafkaConstants;
import org.apache.camel.component.kafka.consumer.KafkaManualCommit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Consumes {@link #topic()} and hands each record to {@link #handlerUri()}. When the handler
 * throws, the original record (same key, body and headers) is parked on {@code
 * <topic>.<group>.retry.1}; each retry consumer holds the record back until its delay has passed
 * (pausing the partition, never sleeping on the consumer thread: {@link RetryDelayListener}),
 * tries again and moves it on to the next retry topic, then to {@code <topic>.<group>.dlq} (spec
 * 19.2, 6.2). The source partition is never blocked by a failing record.
 *
 * <p>{@code <group>} is this service's consumer group ({@code camel.component.kafka.group-id},
 * the service name): retry and DLQ topics belong to one consumer, so services that consume the
 * same topic never re-read each other's parked records.
 *
 * <p>Subclass it in {@code route/} once per consumed topic and register it as a bean. Topics
 * {@code <topic>.<group>.retry.<n>} and {@code <topic>.<group>.dlq} are declared in {@code
 * deploy/kafka/topics.txt} ({@code retry=<group>}) with the source's partition count and 14 d
 * retention. Logging shows topic, attempt, correlation/txn IDs
 * and the exception class only, never the payload or the exception message (18.3).
 */
public abstract class RetryTopicRouteBuilder extends RouteBuilder {

  private static final Logger log = LoggerFactory.getLogger(RetryTopicRouteBuilder.class);

  /** Headers that would make the producer route the parked record somewhere else. */
  private static final String[] PRODUCER_ROUTING_HEADERS = {
    KafkaConstants.OVERRIDE_TOPIC, KafkaConstants.OVERRIDE_TIMESTAMP, KafkaConstants.PARTITION_KEY
  };

  /** How often a paused retry consumer checks whether its held-back record is due. */
  private static final long RESUME_CHECK_MS = 500;

  /** The service's consumer group, also the middle part of its retry and DLQ topic names. */
  private static final String GROUP_PROPERTY = "{{camel.component.kafka.group-id}}";

  private final KafkaRetryProperties retry;
  private final Clock clock;

  /**
   * @param clock the service's {@link Clock} bean ({@code config/ClockConfig}); stamps {@code
   *     mw-retry-not-before} and decides when a held-back record is due, so both use one time
   */
  protected RetryTopicRouteBuilder(KafkaRetryProperties retry, Clock clock) {
    this.retry = retry;
    this.clock = clock;
  }

  /** Source topic, e.g. {@code mw.txn.request.v1}. */
  protected abstract String topic();

  /** Endpoint that handles one record and throws on failure, e.g. {@code bean:x?method=y}. */
  protected abstract String handlerUri();

  /** Retry topic {@code n} (1-based) of {@code group} for {@code topic}. */
  public static String retryTopic(String topic, String group, int n) {
    return topic + "." + group + ".retry." + n;
  }

  /** Dead-letter topic of {@code group} for {@code topic}. */
  public static String dlqTopic(String topic, String group) {
    return topic + "." + group + ".dlq";
  }

  @Override
  public void configure() {
    String group = getCamelContext().resolvePropertyPlaceholders(GROUP_PROPERTY);
    int attempts = retry.attempts();
    from("kafka:" + topic())
        .routeId(topic() + ".consume")
        .onException(Exception.class)
        .handled(true)
        .useOriginalMessage()
        .process(e -> park(e, 1))
        .to("kafka:" + nextTopic(group, 1))
        .process(RetryTopicRouteBuilder::commit)
        .end()
        .to(handlerUri())
        .process(RetryTopicRouteBuilder::commit);

    for (int n = 1; n <= attempts; n++) {
      int next = n + 1;
      RetryDelayListener delay = new RetryDelayListener(retry.delay(n), clock);
      from("kafka:" + retryTopic(topic(), group, n)
              + "?groupId=" + group + ".retry." + n
              + "&pollTimeoutMs=" + RESUME_CHECK_MS)
          .routeId(topic() + ".retry." + n + ".consume")
          .onException(Exception.class)
          .handled(true)
          .useOriginalMessage()
          .process(e -> park(e, next))
          .to("kafka:" + nextTopic(group, next))
          .process(RetryTopicRouteBuilder::commit)
          .end()
          .pausable(delay, ignored -> true)
          .filter(e -> !delay.holdBack(e.getMessage()))
          .to(handlerUri())
          .process(RetryTopicRouteBuilder::commit)
          .end();
    }
  }

  /** Where a record that failed on attempt {@code attempt - 1} goes next. */
  private String nextTopic(String group, int attempt) {
    return attempt <= retry.attempts() ? retryTopic(topic(), group, attempt) : dlqTopic(topic(), group);
  }

  void park(Exchange exchange, int attempt) {
    Exception cause = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class);
    String errorClass = cause == null ? "unknown" : cause.getClass().getName();
    boolean dlq = attempt > retry.attempts();
    Message msg = exchange.getMessage();
    for (String h : PRODUCER_ROUTING_HEADERS) {
      msg.removeHeader(h);
    }
    msg.setHeader(MwHeaders.RETRY_ATTEMPT, Integer.toString(attempt));
    msg.setHeader(MwHeaders.ORIGINAL_TOPIC, topic());
    msg.setHeader(MwHeaders.ERROR_CLASS, errorClass);
    if (dlq) {
      msg.removeHeader(MwHeaders.RETRY_NOT_BEFORE);
    } else {
      long notBefore = clock.millis() + retry.delay(attempt).toMillis();
      msg.setHeader(MwHeaders.RETRY_NOT_BEFORE, Long.toString(notBefore));
    }
    log.atWarn()
        .addKeyValue("event", dlq ? "kafka.dlq.parked" : "kafka.retry.scheduled")
        .addKeyValue("topic", topic())
        .addKeyValue("attempt", attempt)
        .addKeyValue("correlationId", MwHeaders.text(msg.getHeader(MwHeaders.CORRELATION_ID)))
        .addKeyValue("txnId", MwHeaders.text(msg.getHeader(MwHeaders.TXN_ID)))
        .addKeyValue("errorClass", errorClass)
        .log(dlq ? "record moved to DLQ" : "record moved to retry topic");
  }

  /**
   * Commits this record's offset, synchronously, once it is handled or parked on the next topic
   * (auto-commit is off, see {@code application.yml}). A held-back record is never committed, nor
   * is a record polled but not processed when the consumer stops.
   */
  private static void commit(Exchange exchange) {
    KafkaManualCommit commit =
        exchange.getMessage().getHeader(KafkaConstants.MANUAL_COMMIT, KafkaManualCommit.class);
    if (commit == null) {
      throw new IllegalStateException("Kafka manual commit is not enabled for this consumer");
    }
    commit.commit();
  }
}
