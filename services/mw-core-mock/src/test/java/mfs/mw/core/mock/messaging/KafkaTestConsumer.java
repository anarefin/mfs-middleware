package mfs.mw.core.mock.messaging;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Predicate;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;

/** Reads records straight from Kafka, outside Camel, so tests see exactly what was published. */
public final class KafkaTestConsumer {

  private KafkaTestConsumer() {}

  /** Polls {@code topic} from the start until {@code count} records arrived or the timeout ends. */
  public static List<ConsumerRecord<String, String>> read(
      String bootstrap, String topic, int count, Duration timeout) {
    return read(bootstrap, topic, count, timeout, r -> true);
  }

  /** As {@link #read(String, String, int, Duration)}, keeping only records that match. */
  public static List<ConsumerRecord<String, String>> read(
      String bootstrap,
      String topic,
      int count,
      Duration timeout,
      Predicate<ConsumerRecord<String, String>> filter) {
    Properties p = new Properties();
    p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
    p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    // A short fetch wait: a record that arrives mid-poll is returned at once.
    p.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, "50");
    List<ConsumerRecord<String, String>> out = new ArrayList<>();
    // No consumer group: the partitions are assigned and read from the start, so a read never
    // waits for a group join (nothing is committed; each read sees the whole topic).
    try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(p)) {
      long deadline = System.nanoTime() + timeout.toNanos();
      List<TopicPartition> partitions = List.of();
      while (partitions.isEmpty() && System.nanoTime() < deadline) {
        // empty until the topic exists (it may be auto-created by the first send)
        partitions =
            consumer.partitionsFor(topic, Duration.ofSeconds(5)).stream()
                .map(i -> new TopicPartition(topic, i.partition()))
                .toList();
      }
      consumer.assign(partitions);
      consumer.seekToBeginning(partitions);
      while (out.size() < count && System.nanoTime() < deadline) {
        for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(200))) {
          if (filter.test(r)) {
            out.add(r);
          }
        }
      }
    }
    return out;
  }

  /** Last value of each header as UTF-8 text. */
  public static Map<String, String> headers(ConsumerRecord<?, ?> record) {
    Map<String, String> out = new java.util.LinkedHashMap<>();
    for (Header h : record.headers()) {
      out.put(h.key(), h.value() == null ? null : new String(h.value(), StandardCharsets.UTF_8));
    }
    return out;
  }
}
