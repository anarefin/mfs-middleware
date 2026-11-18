package mfs.mw.adapter.billpay;

import mfs.mw.adapter.billpay.messaging.KafkaTestTopics;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * The topics this service consumes, with the retry topics and DLQ of the context's consumer group,
 * created before the listener containers start (the platform declares them in {@code
 * deploy/kafka/topics.txt}; the service never creates topics). Import it beside {@link
 * TestInfrastructure} in a test that switches a consumer on.
 */
@TestConfiguration(proxyBeanMethods = false)
public class BillpayTestTopics {

  /** A marker bean: creating it creates the topics. */
  public record Created(String group) {}

  @Bean
  Created billpayTopics(@Value("${spring.kafka.consumer.group-id}") String group) {
    KafkaTestTopics.createWithRetries("mw.partner.request.billpay.v1", group, 3, 1);
    KafkaTestTopics.create(1, java.util.List.of("mw.config.changed.v1", "mw.partner.reply.v1", "mw.audit.event.v1"));
    return new Created(group);
  }
}
