package mfs.mw.checkout;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.GroupState;

/**
 * Kafka set-up for tests whose consumers start at the latest offset: topics exist before the
 * context starts, and a test can wait until a consumer group has its partitions.
 */
public final class TestTopics {

  private TestTopics() {}

  public static String bootstrap() {
    TestInfrastructure.start();
    return TestInfrastructure.KAFKA.getBootstrapServers();
  }

  /** Creates the topics (3 partitions) that do not exist yet. */
  public static void ensure(String... topics) {
    try (Admin admin = admin()) {
      Set<String> existing = admin.listTopics().names().get();
      List<NewTopic> missing =
          Arrays.stream(topics)
              .filter(t -> !existing.contains(t))
              .map(t -> new NewTopic(t, 3, (short) 1))
              .toList();
      admin.createTopics(missing).all().get();
    } catch (java.util.concurrent.ExecutionException e) {
      // created meanwhile (a consumer of the running context auto-created it): fine
      if (!(e.getCause() instanceof org.apache.kafka.common.errors.TopicExistsException)) {
        throw new IllegalStateException("could not create test topics", e);
      }
    } catch (Exception e) {
      throw new IllegalStateException("could not create test topics", e);
    }
  }

  /** Waits until {@code groupId} is STABLE with at least one member holding partitions. */
  public static void awaitGroupStable(String groupId, Duration timeout) {
    long deadline = System.nanoTime() + timeout.toNanos();
    try (Admin admin = admin()) {
      while (System.nanoTime() < deadline) {
        Map<String, ConsumerGroupDescription> d =
            admin.describeConsumerGroups(List.of(groupId)).all().get();
        ConsumerGroupDescription g = d.get(groupId);
        if (g != null
            && g.groupState() == GroupState.STABLE
            && g.members().stream().anyMatch(m -> !m.assignment().topicPartitions().isEmpty())) {
          return;
        }
        Thread.sleep(200);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (Exception e) {
      throw new IllegalStateException("could not describe group " + groupId, e);
    }
    throw new IllegalStateException("consumer group " + groupId + " not stable in " + timeout);
  }

  private static Admin admin() {
    Properties p = new Properties();
    p.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap());
    return Admin.create(p);
  }
}
