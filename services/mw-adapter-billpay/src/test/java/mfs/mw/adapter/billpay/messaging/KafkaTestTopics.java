package mfs.mw.adapter.billpay.messaging;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import mfs.mw.adapter.billpay.TestInfrastructure;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.errors.TopicExistsException;

/**
 * Creates test topics the way the platform declares them ({@code deploy/kafka/topics.txt}): a
 * consumed topic together with its {@code <topic>.<group>.retry.1..n} and {@code
 * <topic>.<group>.dlq} topics, all with the same partition count. The service never creates
 * topics itself (retry topics are not auto-created), so a test that consumes creates them before
 * its listener containers start, for example in a {@code @Bean} method of its test configuration.
 */
public final class KafkaTestTopics {

  private KafkaTestTopics() {}

  /** {@code <topic>.<group>.retry.<n>}, written out as the platform does. */
  public static String retryTopic(String topic, String group, int n) {
    return topic + "." + group + ".retry." + n;
  }

  /** {@code <topic>.<group>.dlq}. */
  public static String dlqTopic(String topic, String group) {
    return topic + "." + group + ".dlq";
  }

  /** The topic, its {@code retries} retry topics and its DLQ for {@code group}; existing ones are kept. */
  public static void createWithRetries(String topic, String group, int retries, int partitions) {
    List<String> names = new ArrayList<>();
    names.add(topic);
    for (int n = 1; n <= retries; n++) {
      names.add(retryTopic(topic, group, n));
    }
    names.add(dlqTopic(topic, group));
    create(partitions, names);
  }

  /** Creates each topic with {@code partitions} partitions unless it exists. */
  public static void create(int partitions, List<String> names) {
    try (Admin admin =
        Admin.create(
            Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, TestInfrastructure.kafkaBootstrapServers()))) {
      for (String name : names) {
        try {
          admin.createTopics(List.of(new NewTopic(name, partitions, (short) 1))).all().get();
        } catch (ExecutionException e) {
          if (!(e.getCause() instanceof TopicExistsException)) {
            throw new IllegalStateException("cannot create topic " + name, e);
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("interrupted while creating topic " + name, e);
        }
      }
    }
  }
}
