package mfs.mw.ussd.messaging;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import mfs.mw.ussd.config.MwServiceProperties;
import org.apache.camel.AsyncProducer;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.component.kafka.KafkaConstants;
import org.apache.camel.support.DefaultExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes one JSON record to Kafka with the spec 6.1 headers, through the Camel Kafka component
 * (idempotent producer, {@code acks=all}; see {@code application.yml}). The partition key is the
 * caller's ({@code txnId} for transaction topics, {@code msisdn} for USSD/notify). Payloads are
 * never logged.
 *
 * <p>A service that also changes PostgreSQL state publishes through {@link OutboxWriter}
 * instead (spec 6.4); {@link OutboxRelay} then sends the stored record with {@link
 * #publishStoredAsync}.
 */
@Component
public class EventPublisher {

  private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);

  private final ProducerTemplate producer;
  private final JsonMapper json;
  private final Tracer tracer;
  private final Propagator propagator;
  private final String source;
  private final ConcurrentMap<String, AsyncProducer> storedProducers = new ConcurrentHashMap<>();

  public EventPublisher(
      ProducerTemplate producer,
      JsonMapper json,
      Tracer tracer,
      Propagator propagator,
      MwServiceProperties service) {
    this.producer = producer;
    this.json = json;
    this.tracer = tracer;
    this.propagator = propagator;
    this.source = service.name();
  }

  /** Sends {@code payload} as JSON to {@code topic}, synchronously (fails if Kafka rejects it). */
  public void publish(String topic, String key, EventMetadata meta, Object payload) {
    publish(topic, key, meta, payload, Map.of());
  }

  /** As {@link #publish(String, String, EventMetadata, Object)} with extra record headers. */
  public void publish(
      String topic, String key, EventMetadata meta, Object payload, Map<String, String> extra) {
    String body = json.writeValueAsString(payload);
    Span span = tracer.nextSpan().name("kafka publish " + topic).start();
    try {
      Map<String, String> headers = headers(meta, span);
      headers.putAll(extra);
      send(topic, key, headers, body);
      log.atDebug()
          .addKeyValue("event", "kafka.published")
          .addKeyValue("topic", topic)
          .addKeyValue("correlationId", meta.correlationId())
          .addKeyValue("txnId", meta.txnId())
          .log("published record");
    } finally {
      span.end();
    }
  }

  /**
   * The 6.1 headers for a record published later by the outbox relay. Its {@code traceparent}
   * is a child of the current span, so the relayed record continues the business trace.
   */
  public Map<String, String> headersFor(String topic, EventMetadata meta) {
    Span span = tracer.nextSpan().name("outbox append " + topic).start();
    try {
      return headers(meta, span);
    } finally {
      span.end();
    }
  }

  /**
   * Sends a stored record unchanged (same key, headers and JSON body) without waiting: the
   * record is handed to the Kafka producer on the calling thread, in call order, and the future
   * completes once Kafka acknowledged it, or exceptionally if it did not. Records for one key go
   * to one partition through one idempotent producer ({@code acks=all}, at most 5 requests in
   * flight), so Kafka keeps their call order, retries included.
   *
   * <p>Not through {@link ProducerTemplate#asyncSend}: that hands each send to a thread pool,
   * which reorders them. The Camel Kafka producer per topic is created once, started and stopped
   * with the Camel context.
   */
  public CompletableFuture<Void> publishStoredAsync(
      String topic, String key, Map<String, String> headers, String body) {
    CamelContext camel = producer.getCamelContext();
    Exchange exchange = new DefaultExchange(camel);
    exchange.getMessage().setHeaders(new HashMap<>(headers));
    exchange.getMessage().setHeader(KafkaConstants.KEY, key);
    exchange.getMessage().setBody(body);
    CompletableFuture<Void> acked = new CompletableFuture<>();
    try {
      storedProducer(camel, topic)
          .process(
              exchange,
              doneSync -> {
                if (exchange.getException() == null) {
                  acked.complete(null);
                } else {
                  acked.completeExceptionally(exchange.getException());
                }
              });
    } catch (RuntimeException e) {
      acked.completeExceptionally(e);
    }
    return acked;
  }

  private AsyncProducer storedProducer(CamelContext camel, String topic) {
    return storedProducers.computeIfAbsent(
        topic,
        t -> {
          try {
            AsyncProducer p = camel.getEndpoint("kafka:" + t).createAsyncProducer();
            camel.addService(p, true, true);
            return p;
          } catch (Exception e) {
            throw new IllegalStateException("cannot create the Kafka producer for " + t, e);
          }
        });
  }

  private Map<String, String> headers(EventMetadata meta, Span span) {
    Map<String, String> headers = new HashMap<>();
    headers.put(MwHeaders.CORRELATION_ID, meta.correlationId());
    headers.put(MwHeaders.TXN_ID, meta.txnId());
    headers.put(MwHeaders.IDEMPOTENCY_KEY, meta.idempotencyKey());
    headers.put(MwHeaders.SOURCE, source);
    headers.put(MwHeaders.SCHEMA_VERSION, Integer.toString(meta.schemaVersion()));
    propagator.inject(span.context(), headers, (carrier, name, value) -> carrier.put(name, value));
    return headers;
  }

  private void send(String topic, String key, Map<String, String> recordHeaders, String body) {
    Map<String, Object> headers = new HashMap<>(recordHeaders);
    headers.put(KafkaConstants.KEY, key);
    producer.sendBodyAndHeaders("kafka:" + topic, body, headers);
  }
}
