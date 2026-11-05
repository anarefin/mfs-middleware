package mfs.mw.admin.api.messaging;

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
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;

/**
 * Reads records straight from Kafka, outside Camel, so tests see exactly what was published. It
 * assigns the topic's partitions itself (no consumer group), so a read never waits for a group
 * join or rebalance.
 */
public final class KafkaTestConsumer {

  private static final Duration POLL = Duration.ofMillis(100);

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
    List<ConsumerRecord<String, String>> out = new ArrayList<>();
    long deadline = System.nanoTime() + timeout.toNanos();
    try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props(bootstrap))) {
      // The topic may not exist yet (created by the first record written to it).
      while (consumer.assignment().isEmpty() && System.nanoTime() < deadline) {
        if (!assignAll(consumer, topic)) {
          sleep();
        }
      }
      while (out.size() < count && System.nanoTime() < deadline) {
        collect(consumer, filter, out);
      }
    }
    return out;
  }

  /**
   * Every matching record already on {@code topic}: reads from the start up to the end offsets
   * seen when called, then returns, so it never waits out a timeout. Use it to assert that
   * nothing (or nothing more) was published, once the code under test has finished writing
   * (publishing is synchronous, {@code acks=all}). A topic that does not exist yet is empty.
   */
  public static List<ConsumerRecord<String, String>> readToEnd(
      String bootstrap, String topic, Predicate<ConsumerRecord<String, String>> filter) {
    List<ConsumerRecord<String, String>> out = new ArrayList<>();
    try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props(bootstrap))) {
      if (!assignAll(consumer, topic)) {
        return out;
      }
      Map<TopicPartition, Long> end = consumer.endOffsets(consumer.assignment());
      long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
      while (!reached(consumer, end)) {
        if (System.nanoTime() > deadline) {
          throw new AssertionError("could not read " + topic + " up to its end offsets " + end);
        }
        collect(consumer, filter, out);
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

  private static Properties props(String bootstrap) {
    Properties p = new Properties();
    p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
    p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
    // Reading must not create the topic: a missing topic simply has no records.
    p.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, "false");
    p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    return p;
  }

  /** Assigns every partition of {@code topic} from its start; false if the topic has none yet. */
  private static boolean assignAll(KafkaConsumer<String, String> consumer, String topic) {
    List<PartitionInfo> infos = consumer.partitionsFor(topic, Duration.ofSeconds(5));
    if (infos == null || infos.isEmpty()) {
      return false;
    }
    List<TopicPartition> partitions =
        infos.stream().map(i -> new TopicPartition(i.topic(), i.partition())).toList();
    consumer.assign(partitions);
    consumer.seekToBeginning(partitions);
    return true;
  }

  private static boolean reached(KafkaConsumer<String, String> consumer, Map<TopicPartition, Long> end) {
    return end.entrySet().stream().allMatch(e -> consumer.position(e.getKey()) >= e.getValue());
  }

  private static void collect(
      KafkaConsumer<String, String> consumer,
      Predicate<ConsumerRecord<String, String>> filter,
      List<ConsumerRecord<String, String>> out) {
    for (ConsumerRecord<String, String> r : consumer.poll(POLL)) {
      if (filter.test(r)) {
        out.add(r);
      }
    }
  }

  private static void sleep() {
    try {
      Thread.sleep(POLL.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while waiting for a topic", e);
    }
  }
}
