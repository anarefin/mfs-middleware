package mfs.mw.auth.messaging;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * The two topics mw-auth consumes, with their retry topics and DLQs of group {@code mw-auth}, one
 * partition each (so the order per key is the order on the topic), created before the listener
 * containers subscribe, as the platform declares them ({@code deploy/kafka/topics.txt}); the
 * service never creates topics itself.
 */
@TestConfiguration(proxyBeanMethods = false)
public class AuthConsumedTopics {

  static final String GROUP = "mw-auth";

  @Bean
  String authConsumedTopics() {
    KafkaTestTopics.createWithRetries(CoreWalletEventListener.TOPIC, GROUP, 3, 1);
    KafkaTestTopics.createWithRetries(WalletLockListener.TOPIC, GROUP, 3, 1);
    return GROUP;
  }
}
