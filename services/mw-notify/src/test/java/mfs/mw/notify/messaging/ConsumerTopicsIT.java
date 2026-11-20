package mfs.mw.notify.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import mfs.mw.notify.TestInfrastructure;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

/**
 * The service's consumed topics (ADR D27): each has a listener in the service's consumer
 * group with its three retry consumers and its DLQ under the platform's names ({@code
 * deploy/kafka/topics.txt}, {@code retry=mw-notify}), and {@code
 * mw.notify.consumers.enabled=false} (the test default) keeps every one of them stopped.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestInfrastructure.class)
class ConsumerTopicsIT {

  static final String GROUP = "mw-notify";
  static final List<String> TOPICS =
      List.of("mw.notify.request.v1", "mw.txn.status.v1", "mw.config.changed.v1", "mw.notify.push-token.v1",
          "auth.wallet-lock.v1", "mw.notify.opt-out.v1");

  private final KafkaListenerEndpointRegistry registry;

  @Autowired
  ConsumerTopicsIT(KafkaListenerEndpointRegistry registry) {
    this.registry = registry;
  }

  /** Topic -> consumer group of every listener container. */
  Map<String, String> groupsByTopic() {
    Map<String, String> out = new HashMap<>();
    for (MessageListenerContainer c : registry.getListenerContainers()) {
      for (String topic : c.getContainerProperties().getTopics()) {
        out.put(topic, c.getGroupId());
      }
    }
    return out;
  }

  @Test
  void everyConsumedTopicHasItsRetryConsumersAndDlqInTheServiceGroup() {
    Map<String, String> groups = groupsByTopic();
    for (String topic : TOPICS) {
      assertThat(groups).as(topic).containsEntry(topic, GROUP);
      for (int n = 1; n <= 3; n++) {
        assertThat(groups)
            .as(topic + " retry " + n)
            .containsEntry(topic + "." + GROUP + ".retry." + n, GROUP + ".retry." + n);
      }
      assertThat(groups).as(topic + " dlq").containsKey(topic + "." + GROUP + ".dlq");
    }
  }

  @Test
  void noOtherTopicIsConsumed() {
    assertThat(groupsByTopic().keySet())
        .allSatisfy(t -> assertThat(TOPICS.stream().anyMatch(t::startsWith)).as(t).isTrue());
  }

  @Test
  void consumersSwitchedOffStartNoContainer() {
    assertThat(registry.getListenerContainers()).isNotEmpty().noneMatch(MessageListenerContainer::isRunning);
  }
}
