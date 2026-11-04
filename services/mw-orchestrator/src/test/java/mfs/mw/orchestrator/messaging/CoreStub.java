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
 * The orchestrator's own stand-in for the core (ticket 08): reads {@code core.wallet.command.v1}
 * and answers on {@code core.wallet.event.v1} by a fixed rule, with no knowledge of mw-core-mock.
 * ValidateTransaction and PostTransfer succeed, except that an amount of {@link #REJECT_AMOUNT}
 * is refused at validation with INSUFFICIENT_BALANCE and {@link #SILENT_AMOUNT} gets no answer to
 * PostTransfer (a core that never replies).
 */
public final class CoreStub implements AutoCloseable {

  public static final long REJECT_AMOUNT = 99_999;
  public static final long SILENT_AMOUNT = 77_777;

  private final JsonMapper json = JsonMapper.builder().build();
  private final KafkaConsumer<String, String> consumer;
  private final KafkaProducer<String, String> producer;
  private final AtomicBoolean running = new AtomicBoolean(true);
  private final Thread thread;
  private final List<JsonNode> commands = new CopyOnWriteArrayList<>();

  public CoreStub(String bootstrap) {
    Properties c = new Properties();
    c.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
    c.put(ConsumerConfig.GROUP_ID_CONFIG, "core-stub-" + UUID.randomUUID());
    c.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    c.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    c.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    consumer = new KafkaConsumer<>(c);
    Properties p = new Properties();
    p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
    p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    producer = new KafkaProducer<>(p);
    consumer.subscribe(List.of("core.wallet.command.v1"));
    thread = Thread.ofPlatform().daemon().name("core-stub").start(this::loop);
  }

  /** Every command seen so far. */
  public List<JsonNode> commands() {
    return commands;
  }

  private void loop() {
    while (running.get()) {
      for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(200))) {
        JsonNode cmd = json.readTree(r.value());
        commands.add(cmd);
        Map<String, Object> event = answer(cmd);
        if (event != null) {
          producer.send(
              new ProducerRecord<>("core.wallet.event.v1", r.key(), json.writeValueAsString(event)));
        }
      }
    }
  }

  private Map<String, Object> answer(JsonNode cmd) {
    String type = cmd.path("commandType").asString();
    long amount = cmd.path("payload").path("amount").path("valueMinor").asLong();
    boolean reject = amount == REJECT_AMOUNT && "ValidateTransaction".equals(type);
    if ("PostTransfer".equals(type) && amount == SILENT_AMOUNT) {
      return null;
    }
    String eventType =
        switch (type) {
          case "ValidateTransaction" -> reject ? "TransactionRejected" : "TransactionValidated";
          case "PostTransfer" -> "TransferPosted";
          default -> null;
        };
    if (eventType == null) {
      return null;
    }
    Map<String, Object> e = new LinkedHashMap<>();
    e.put("schemaVersion", 1);
    e.put("eventType", eventType);
    e.put("eventId", UUID.randomUUID().toString());
    e.put("commandId", cmd.path("commandId").asString());
    e.put("txnId", cmd.path("txnId").asString());
    e.put("coreRef", "STUB-" + cmd.path("txnId").asString().substring(0, 8));
    e.put("occurredAt", Instant.now().toString());
    e.put("outcome", reject ? "FAILURE" : "SUCCESS");
    if (reject) {
      e.put("reasonCode", "INSUFFICIENT_BALANCE");
      e.put("reasonMessage", "stub");
    }
    e.put(
        "payload",
        "TransactionValidated".equals(eventType)
            ? Map.of("fee", Map.of("valueMinor", 500, "currency", "BDT"))
            : Map.of());
    return e;
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
