package mfs.mw.adapter.telco.messaging;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import mfs.mw.adapter.telco.config.MwKafkaProperties;
import mfs.mw.adapter.telco.config.MwServiceProperties;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes one JSON record to Kafka with the spec 6.1 headers through Spring Kafka's {@link
 * KafkaTemplate} (idempotent producer, {@code acks=all}, at most 5 requests in flight; see {@code
 * application.yml} and {@code config/KafkaConfig}, ADR D27). The partition key is the caller's
 * ({@code txnId} for transaction topics, {@code msisdn} for USSD/notify). Payloads are never
 * logged.
 *
 * <p>Two templates on the same producer (so one producer keeps the send order):
 *
 * <ul>
 *   <li>{@link #publish} sends through the observed template: its Micrometer observation opens
 *       the producer span as a child of the caller's span and writes that span as the record's
 *       {@code traceparent}.
 *   <li>{@link #publishStoredAsync} sends through the forwarding template (no observation), so a
 *       stored record goes out with exactly the headers it was stored with; its {@code
 *       traceparent} still points at the transaction that wrote it.
 * </ul>
 *
 * <p>A service that also changes PostgreSQL state publishes through {@link OutboxWriter} instead
 * (spec 6.4); {@link OutboxRelay} then sends the stored record with {@link #publishStoredAsync}.
 */
@Component
public class EventPublisher {

  private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);

  private final KafkaTemplate<String, String> live;
  private final KafkaTemplate<String, String> forwarding;
  private final JsonMapper json;
  private final Tracer tracer;
  private final Propagator propagator;
  private final String source;
  private final Duration sendTimeout;

  public EventPublisher(
      KafkaTemplate<String, String> kafkaTemplate,
      @Qualifier("forwardingKafkaTemplate") KafkaTemplate<String, String> forwarding,
      JsonMapper json,
      Tracer tracer,
      Propagator propagator,
      MwServiceProperties service,
      MwKafkaProperties kafka) {
    this.live = kafkaTemplate;
    this.forwarding = forwarding;
    this.json = json;
    this.tracer = tracer;
    this.propagator = propagator;
    this.source = service.name();
    this.sendTimeout = kafka.sendTimeout();
  }

  /**
   * Sends {@code payload} as JSON to {@code topic} and waits for Kafka's acknowledgement (at most
   * {@code mw.kafka.send-timeout}); throws if Kafka did not acknowledge it. The {@code
   * traceparent} is the producer span's, a child of the current span.
   */
  public void publish(String topic, String key, EventMetadata meta, Object payload) {
    String body = json.writeValueAsString(payload);
    ProducerRecord<String, String> record =
        new ProducerRecord<>(topic, null, key, body, toRecordHeaders(baseHeaders(meta)));
    try {
      live.send(record).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new KafkaException("interrupted while publishing to " + topic, e);
    } catch (ExecutionException e) {
      throw new KafkaException("Kafka did not acknowledge the record for " + topic, e.getCause());
    } catch (TimeoutException e) {
      throw new KafkaException("no acknowledgement for " + topic + " within " + sendTimeout, e);
    }
    log.atDebug()
        .addKeyValue("event", "kafka.published")
        .addKeyValue("topic", topic)
        .addKeyValue("correlationId", meta.correlationId())
        .addKeyValue("txnId", meta.txnId())
        .log("published record");
  }

  /**
   * The 6.1 headers for a record published later by the outbox relay. Its {@code traceparent}
   * is a child of the current span, so the relayed record continues the business trace.
   */
  public Map<String, String> headersFor(String topic, EventMetadata meta) {
    Map<String, String> headers = baseHeaders(meta);
    Span span = tracer.nextSpan().name("outbox append " + topic).start();
    try {
      propagator.inject(span.context(), headers, (carrier, name, value) -> carrier.put(name, value));
      return headers;
    } finally {
      span.end();
    }
  }

  /**
   * Sends a stored record unchanged (same key, headers and JSON body) without waiting: the record
   * is handed to the Kafka producer on the calling thread, in call order, and the future completes
   * once Kafka acknowledged it, or exceptionally if it did not (also when the send is refused
   * before it reaches the producer). Records for one key go to one partition through one
   * idempotent producer ({@code acks=all}, at most 5 requests in flight), so Kafka keeps their
   * call order, retries included.
   */
  public CompletableFuture<Void> publishStoredAsync(
      String topic, String key, Map<String, String> headers, String body) {
    try {
      return forwarding
          .send(new ProducerRecord<>(topic, null, key, body, toRecordHeaders(headers)))
          .thenApply(result -> null);
    } catch (RuntimeException e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  /** The 6.1 headers apart from {@code traceparent}. */
  private Map<String, String> baseHeaders(EventMetadata meta) {
    Map<String, String> headers = new HashMap<>();
    headers.put(MwHeaders.CORRELATION_ID, meta.correlationId());
    headers.put(MwHeaders.TXN_ID, meta.txnId());
    headers.put(MwHeaders.IDEMPOTENCY_KEY, meta.idempotencyKey());
    headers.put(MwHeaders.SOURCE, source);
    headers.put(MwHeaders.SCHEMA_VERSION, Integer.toString(meta.schemaVersion()));
    return headers;
  }

  private static Headers toRecordHeaders(Map<String, String> headers) {
    RecordHeaders out = new RecordHeaders();
    headers.forEach((name, value) -> out.add(name, value == null ? null : value.getBytes(StandardCharsets.UTF_8)));
    return out;
  }
}
