package mfs.mw.service.template.route;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import mfs.mw.service.template.config.KafkaRetryProperties;
import mfs.mw.service.template.messaging.MwHeaders;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.builder.RouteBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Consumes {@link #topic()} and hands each record to {@link #handlerUri()}. When the handler
 * throws, the original record (same key, body and headers) is parked on {@code
 * <topic>.retry.1}; each retry consumer waits for its delay, tries again and moves the record on
 * to the next retry topic, then to {@code <topic>.dlq} (spec 19.2, 6.2). The source partition is
 * never blocked by a failing record.
 *
 * <p>Subclass it in {@code route/} once per consumed topic and register it as a bean. Topics
 * {@code <topic>.retry.<n>} and {@code <topic>.dlq} are declared in {@code deploy/kafka/} with the
 * source's partition count and 14 d retention. Logging shows topic, attempt, correlation/txn IDs
 * and the exception class only, never the payload or the exception message (18.3).
 */
public abstract class RetryTopicRouteBuilder extends RouteBuilder {

  private static final Logger log = LoggerFactory.getLogger(RetryTopicRouteBuilder.class);

  /** Headers of the consumed record that the producer would otherwise reuse for routing. */
  private static final String[] CONSUMER_ROUTING_HEADERS = {
    "kafka.OVERRIDE_TOPIC", "kafka.OVERRIDE_TIMESTAMP", "kafka.PARTITION_KEY"
  };

  private final KafkaRetryProperties retry;

  protected RetryTopicRouteBuilder(KafkaRetryProperties retry) {
    this.retry = retry;
  }

  /** Source topic, e.g. {@code mw.txn.request.v1}. */
  protected abstract String topic();

  /** Endpoint that handles one record and throws on failure, e.g. {@code bean:x?method=y}. */
  protected abstract String handlerUri();

  @Override
  public void configure() {
    int attempts = retry.attempts();
    from("kafka:" + topic())
        .routeId(topic() + ".consume")
        .onException(Exception.class)
        .handled(true)
        .useOriginalMessage()
        .process(e -> park(e, 1))
        .to("kafka:" + nextTopic(1))
        .end()
        .to(handlerUri());

    for (int n = 1; n <= attempts; n++) {
      int next = n + 1;
      long delayMs = retry.delay(n).toMillis();
      from("kafka:" + retryTopic(n)
              + "?groupId={{camel.component.kafka.group-id}}.retry." + n
              + "&maxPollRecords=20"
              + "&maxPollIntervalMs=" + (delayMs + Duration.ofMinutes(5).toMillis()))
          .routeId(retryTopic(n) + ".consume")
          .onException(Exception.class)
          .handled(true)
          .useOriginalMessage()
          .process(e -> park(e, next))
          .to("kafka:" + nextTopic(next))
          .end()
          .process(RetryTopicRouteBuilder::waitUntilDue)
          .to(handlerUri());
    }
  }

  private String retryTopic(int n) {
    return topic() + ".retry." + n;
  }

  /** Where a record that failed on attempt {@code attempt - 1} goes next. */
  private String nextTopic(int attempt) {
    return attempt <= retry.attempts() ? retryTopic(attempt) : topic() + ".dlq";
  }

  private void park(Exchange exchange, int attempt) {
    Exception cause = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class);
    String errorClass = cause == null ? "unknown" : cause.getClass().getName();
    boolean dlq = attempt > retry.attempts();
    Message msg = exchange.getMessage();
    for (String h : CONSUMER_ROUTING_HEADERS) {
      msg.removeHeader(h);
    }
    msg.setHeader(MwHeaders.RETRY_ATTEMPT, Integer.toString(attempt));
    msg.setHeader(MwHeaders.ORIGINAL_TOPIC, topic());
    msg.setHeader(MwHeaders.ERROR_CLASS, errorClass);
    if (dlq) {
      msg.removeHeader(MwHeaders.RETRY_NOT_BEFORE);
    } else {
      long notBefore = System.currentTimeMillis() + retry.delay(attempt).toMillis();
      msg.setHeader(MwHeaders.RETRY_NOT_BEFORE, Long.toString(notBefore));
    }
    log.atWarn()
        .addKeyValue("event", dlq ? "kafka.dlq.parked" : "kafka.retry.scheduled")
        .addKeyValue("topic", topic())
        .addKeyValue("attempt", attempt)
        .addKeyValue("correlationId", text(msg, MwHeaders.CORRELATION_ID))
        .addKeyValue("txnId", text(msg, MwHeaders.TXN_ID))
        .addKeyValue("errorClass", errorClass)
        .log(dlq ? "record moved to DLQ" : "record moved to retry topic");
  }

  private static void waitUntilDue(Exchange exchange) throws InterruptedException {
    String notBefore = text(exchange.getMessage(), MwHeaders.RETRY_NOT_BEFORE);
    if (notBefore == null) {
      return;
    }
    long waitMs = Long.parseLong(notBefore) - System.currentTimeMillis();
    if (waitMs > 0) {
      Thread.sleep(waitMs);
    }
  }

  /** Header value as text: Kafka record headers arrive as UTF-8 bytes. */
  private static String text(Message msg, String name) {
    Object v = msg.getHeader(name);
    if (v instanceof byte[] bytes) {
      return new String(bytes, StandardCharsets.UTF_8);
    }
    return v == null ? null : v.toString();
  }
}
