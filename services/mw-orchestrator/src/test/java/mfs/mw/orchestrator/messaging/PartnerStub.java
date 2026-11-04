package mfs.mw.orchestrator.messaging;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The orchestrator's own stand-in for the telco adapter (ticket 17): reads {@code
 * mw.partner.request.telco.v1} and answers on {@code mw.partner.reply.v1}. A credit MSISDN ending
 * in {@code 999} is refused ({@code MW-PTR-<PARTNER>-TOPUP_REJECTED}), anything else succeeds. For
 * a credit MSISDN ending in {@code 888} the PAY reply is lost (an adapter crash after hand-over,
 * ticket 16): nothing is answered, and a later STATUS_QUERY reports the top-up as done.
 */
public final class PartnerStub implements AutoCloseable {

  private final JsonMapper json = JsonMapper.builder().build();
  private final KafkaConsumer<String, String> consumer;
  private final KafkaProducer<String, String> producer;
  private final AtomicBoolean running = new AtomicBoolean(true);
  private final Thread thread;
  private final List<JsonNode> requests = new CopyOnWriteArrayList<>();

  public PartnerStub(String bootstrap) {
    Properties c = new Properties();
    c.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
    c.put(ConsumerConfig.GROUP_ID_CONFIG, "partner-stub-" + UUID.randomUUID());
    c.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    c.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    c.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    consumer = new KafkaConsumer<>(c);
    Properties p = new Properties();
    p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
    p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    producer = new KafkaProducer<>(p);
    consumer.subscribe(List.of("mw.partner.request.telco.v1"));
    thread = Thread.ofPlatform().daemon().name("partner-stub").start(this::loop);
  }

  /** Every request seen so far. */
  public List<JsonNode> requests() {
    return requests;
  }

  private void loop() {
    while (running.get()) {
      for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(200))) {
        JsonNode req = json.readTree(r.value());
        requests.add(req);
        if (lost(req)) {
          continue;
        }
        producer.send(new ProducerRecord<>("mw.partner.reply.v1", r.key(), json.writeValueAsString(answer(req))));
      }
    }
  }

  private static boolean lost(JsonNode req) {
    return "PAY".equals(req.path("payload").path("operation").asString())
        && req.path("payload").path("creditParty").path("id").asString().endsWith("888");
  }

  private Map<String, Object> answer(JsonNode req) {
    String partnerId = req.path("payload").path("partner").path("partnerId").asString();
    boolean refuse = req.path("payload").path("creditParty").path("id").asString().endsWith("999");
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("operation", req.path("payload").path("operation").asString());
    payload.put("outcome", refuse ? "FAILED" : "SUCCESS");
    if (refuse) {
      payload.put("errorCode", "MW-PTR-" + partnerId.toUpperCase() + "-TOPUP_REJECTED");
      payload.put("message", "stub refusal");
      payload.put("retryable", false);
    } else {
      payload.put("partnerRef", "OCS-STUB-" + req.path("txnId").asString().substring(0, 8));
    }
    Map<String, Object> reply = new LinkedHashMap<>();
    reply.put("schemaVersion", 1);
    reply.put("family", req.path("family").asString());
    reply.put("requestId", req.path("requestId").asString());
    reply.put("txnId", req.path("txnId").asString());
    reply.put("correlationId", req.path("correlationId").asString());
    reply.put("partnerId", partnerId);
    reply.put("repliedAt", Instant.now().toString());
    reply.put("payload", payload);
    return reply;
  }

  @Override
  public void close() {
    running.set(false);
    try {
      thread.join(5_000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    consumer.close();
    producer.close();
  }
}
