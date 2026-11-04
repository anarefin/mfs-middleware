package mfs.mw.app.gateway.messaging;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
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
import tools.jackson.databind.node.ObjectNode;

/**
 * Plays mw-orchestrator for the gateway's tests: reads {@code mw.txn.request.v1} and answers on
 * {@code mw.txn.status.v1} with the {@code mw-reply-to} of the request, as the orchestrator does
 * (its CLAUDE.md). What it answers depends on the amount (minor units), see {@link #answer}.
 */
public final class OrchestratorStub implements AutoCloseable {

  /** Completes: RECEIVED, VALIDATING, POSTING, COMPLETED. */
  public static final long COMPLETES = 1_000;
  /** Never answers beyond RECEIVED. */
  public static final long SILENT = 2_000;
  /** Core rejects with INSUFFICIENT_BALANCE. */
  public static final long CORE_REJECTS = 3_000;
  /** Repeated key, stored txn already COMPLETED. */
  public static final long DUPLICATE_DONE = 4_000;
  /** Repeated key, stored txn still POSTING. */
  public static final long DUPLICATE_RUNNING = 5_000;
  /** Same key, other content: MW-IDEM-001. */
  public static final long IDEM_CONFLICT = 6_000;
  /** Completes, but addressed to another gateway instance. */
  public static final long OTHER_INSTANCE = 7_000;

  public static final String STORED_TXN_ID = "019a0000-0000-7000-8000-00000000abcd";

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final KafkaConsumer<String, String> consumer;
  private final KafkaProducer<String, String> producer;
  private final AtomicBoolean running = new AtomicBoolean(true);
  private final Thread thread;
  private final List<ConsumerRecord<String, String>> requests =
      Collections.synchronizedList(new ArrayList<>());

  public OrchestratorStub(String bootstrap) {
    Properties c = new Properties();
    c.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
    c.put(ConsumerConfig.GROUP_ID_CONFIG, "orchestrator-stub-" + UUID.randomUUID());
    c.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
    c.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    c.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    consumer = new KafkaConsumer<>(c);
    Properties p = new Properties();
    p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
    p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    producer = new KafkaProducer<>(p);
    consumer.subscribe(List.of("mw.txn.request.v1"));
    // join the group before the first request is sent (offset reset is "latest")
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (consumer.assignment().isEmpty() && System.nanoTime() < deadline) {
      consumer.poll(Duration.ofMillis(100));
    }
    thread = Thread.ofPlatform().name("orchestrator-stub").start(this::loop);
  }

  /** Every request record seen so far. */
  public List<ConsumerRecord<String, String>> requests() {
    synchronized (requests) {
      return List.copyOf(requests);
    }
  }

  private void loop() {
    while (running.get()) {
      for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(100))) {
        requests.add(r);
        answer(r);
      }
    }
    consumer.close();
  }

  private void answer(ConsumerRecord<String, String> r) {
    JsonNode req = JSON.readTree(r.value());
    String replyTo = KafkaTestConsumer.headers(r).get("mw-reply-to");
    String txnId = req.path("txnId").asString();
    long amount = req.path("amount").path("valueMinor").asLong();
    if (amount == DUPLICATE_DONE || amount == DUPLICATE_RUNNING) {
      ObjectNode s = status(req, STORED_TXN_ID, amount == DUPLICATE_DONE ? "COMPLETED" : "POSTING");
      ObjectNode attrs = s.putObject("attributes");
      attrs.put("requestTxnId", txnId);
      attrs.put("duplicate", "true");
      send(STORED_TXN_ID, s, replyTo);
      return;
    }
    if (amount == IDEM_CONFLICT) {
      ObjectNode s = status(req, txnId, "REJECTED");
      error(s, "MW-IDEM-001", "Idempotency key was already used with a different request");
      send(txnId, s, replyTo);
      return;
    }
    send(txnId, status(req, txnId, "RECEIVED"), replyTo);
    if (amount == SILENT) {
      return;
    }
    send(txnId, status(req, txnId, "VALIDATING"), replyTo);
    if (amount == CORE_REJECTS) {
      ObjectNode s = status(req, txnId, "REJECTED");
      error(s, "MW-CORE-INSUFFICIENT_BALANCE", "Insufficient balance");
      ((ObjectNode) s.get("error")).put("messageBn", "অপর্যাপ্ত ব্যালেন্স");
      send(txnId, s, replyTo);
      return;
    }
    send(txnId, status(req, txnId, "POSTING"), replyTo);
    ObjectNode done = status(req, txnId, "COMPLETED");
    done.putObject("fee").put("valueMinor", 500).put("currency", "BDT");
    done.put("coreRef", "CORE-STUB-1");
    send(txnId, done, amount == OTHER_INSTANCE ? "some-other-instance" : replyTo);
  }

  private static ObjectNode status(JsonNode req, String txnId, String status) {
    ObjectNode s = JSON.createObjectNode();
    s.put("schemaVersion", 1);
    s.put("txnId", txnId);
    s.put("idempotencyKey", req.path("idempotencyKey").asString());
    s.put("correlationId", req.path("correlationId").asString());
    s.put("channel", req.path("channel").asString());
    s.put("txnType", req.path("txnType").asString());
    s.put("status", status);
    s.set("initiator", req.path("initiator"));
    s.set("debitParty", req.path("debitParty"));
    s.set("creditParty", req.path("creditParty"));
    s.set("amount", req.path("amount"));
    s.put("requestedAt", req.path("requestedAt").asString());
    s.put("updatedAt", Instant.now().toString());
    return s;
  }

  private static void error(ObjectNode s, String code, String message) {
    s.putObject("error").put("code", code).put("message", message).put("retryable", false);
  }

  private void send(String key, ObjectNode status, String replyTo) {
    ProducerRecord<String, String> rec =
        new ProducerRecord<>("mw.txn.status.v1", key, JSON.writeValueAsString(status));
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("mw-correlation-id", status.path("correlationId").asString());
    headers.put("mw-txn-id", key);
    headers.put("mw-idempotency-key", status.path("idempotencyKey").asString());
    headers.put("mw-source", "mw-orchestrator");
    headers.put("mw-schema-version", "1");
    if (replyTo != null) {
      headers.put("mw-reply-to", replyTo);
    }
    headers.forEach((k, v) -> rec.headers().add(k, v.getBytes(StandardCharsets.UTF_8)));
    producer.send(rec);
    producer.flush();
  }

  @Override
  public void close() {
    running.set(false);
    try {
      thread.join(5_000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    producer.close();
  }
}
