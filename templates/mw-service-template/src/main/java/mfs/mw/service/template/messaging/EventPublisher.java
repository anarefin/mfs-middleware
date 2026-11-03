package mfs.mw.service.template.messaging;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.HashMap;
import java.util.Map;
import mfs.mw.service.template.config.MwServiceProperties;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.component.kafka.KafkaConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes one JSON record to Kafka with the spec 6.1 headers, through the Camel Kafka component
 * (idempotent producer, {@code acks=all}; see {@code application.yml}). The partition key is the
 * caller's ({@code txnId} for transaction topics, {@code msisdn} for USSD/notify). Payloads are
 * never logged.
 */
@Component
public class EventPublisher {

  private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);

  private final ProducerTemplate producer;
  private final JsonMapper json;
  private final Tracer tracer;
  private final Propagator propagator;
  private final String source;

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
    String body = json.writeValueAsString(payload);
    Span span = tracer.nextSpan().name("kafka publish " + topic).start();
    try {
      Map<String, Object> headers = new HashMap<>();
      headers.put(KafkaConstants.KEY, key);
      headers.put(MwHeaders.CORRELATION_ID, meta.correlationId());
      headers.put(MwHeaders.TXN_ID, meta.txnId());
      headers.put(MwHeaders.IDEMPOTENCY_KEY, meta.idempotencyKey());
      headers.put(MwHeaders.SOURCE, source);
      headers.put(MwHeaders.SCHEMA_VERSION, Integer.toString(meta.schemaVersion()));
      propagator.inject(span.context(), headers, (carrier, name, value) -> carrier.put(name, value));
      producer.sendBodyAndHeaders("kafka:" + topic, body, headers);
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
}
