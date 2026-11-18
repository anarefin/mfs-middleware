package mfs.mw.core.mock.messaging;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * The two command topics with their retry topics and DLQs of group {@code mw-core-mock}, created
 * before the listener containers subscribe, as the platform declares them ({@code
 * deploy/kafka/topics.txt}); the service never creates topics itself.
 */
@TestConfiguration(proxyBeanMethods = false)
public class CoreCommandTopics {

  static final String GROUP = "mw-core-mock";

  @Bean
  String coreCommandTopics() {
    KafkaTestTopics.createWithRetries(CoreCommandListener.WALLET_TOPIC, GROUP, 3, 1);
    KafkaTestTopics.createWithRetries(CoreCommandListener.PAYMENT_TOPIC, GROUP, 3, 1);
    return GROUP;
  }
}
