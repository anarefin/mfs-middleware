package mfs.mw.checkout.config;

import java.util.HashMap;
import java.util.Map;
import mfs.mw.checkout.messaging.ConsumerContextInterceptor;
import mfs.mw.checkout.messaging.StatusReplyListener;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties.AckMode;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.listener.RecordInterceptor;

/**
 * The per-instance reply consumer of {@code mw.txn.status.v1} (spec 7.5, ADR D27): every instance
 * reads every status record in its own consumer group {@code <group>.reply.<instanceId>} and
 * {@link StatusReplyListener} keeps the ones addressed to it. A reply only matters to a request
 * waiting right now, so the consumer starts at the latest offset, auto-commits, and is never
 * retried or parked: a record that fails is logged (class only) and skipped.
 *
 * <p>Its own container factory, and a container built here rather than a {@code @KafkaListener}:
 * the retry-topic configuration ({@code KafkaConfig}) applies by topic to every {@code
 * @KafkaListener}, and {@code messaging/StatusProjectionListener} consumes the same topic with
 * retries, so the topic cannot be excluded there. Off when {@code
 * mw.checkout.consumers.enabled=false}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty(name = "mw.checkout.consumers.enabled", matchIfMissing = true)
public class StatusReplyConsumerConfig {

  private static final Logger log = LoggerFactory.getLogger(StatusReplyConsumerConfig.class);

  /** Latest offset, auto-commit, log-and-skip, no retry topics, no manual acknowledgement. */
  @Bean
  public ConcurrentKafkaListenerContainerFactory<String, String> statusReplyContainerFactory(
      ConsumerFactory<String, String> consumerFactory, MwKafkaProperties kafka) {
    ConcurrentKafkaListenerContainerFactory<String, String> factory =
        new ConcurrentKafkaListenerContainerFactory<>();
    // Spring Boot's consumer settings (bootstrap servers, deserializers), then the reply's own.
    Map<String, Object> config = new HashMap<>(consumerFactory.getConfigurationProperties());
    config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
    config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true);
    factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(config));
    factory.setCommonErrorHandler(new LogAndSkip());
    factory.setRecordInterceptor(mdcInterceptor());
    factory.getContainerProperties().setAckMode(AckMode.BATCH);
    factory.getContainerProperties().setObservationEnabled(true);
    factory.getContainerProperties().setShutdownTimeout(kafka.listener().shutdownTimeout().toMillis());
    return factory;
  }

  /** The reply consumer: group {@code <group>.reply.<instanceId>}, one per instance. */
  @Bean
  public ConcurrentMessageListenerContainer<String, String> statusReplyContainer(
      ConcurrentKafkaListenerContainerFactory<String, String> statusReplyContainerFactory,
      StatusReplyListener listener,
      CheckoutProperties props,
      @Value("${spring.kafka.consumer.group-id}") String group) {
    ConcurrentMessageListenerContainer<String, String> container =
        statusReplyContainerFactory.createContainer(props.statusTopic());
    container.getContainerProperties().setGroupId(group + ".reply." + props.instanceId());
    container.getContainerProperties().setMessageListener((MessageListener<String, String>) listener::onReply);
    container.setBeanName("status-reply");
    return container;
  }

  /** The consumer-side MDC ({@link ConsumerContextInterceptor}), which reads headers only. */
  @SuppressWarnings("unchecked")
  private static RecordInterceptor<String, String> mdcInterceptor() {
    RecordInterceptor<?, ?> interceptor = new ConsumerContextInterceptor();
    return (RecordInterceptor<String, String>) interceptor;
  }

  /** Logs the failure (exception class only, never the message or payload) and moves on. */
  static final class LogAndSkip implements CommonErrorHandler {

    @Override
    public boolean handleOne(
        Exception thrownException,
        ConsumerRecord<?, ?> record,
        Consumer<?, ?> consumer,
        MessageListenerContainer container) {
      log.atWarn()
          .addKeyValue("event", "txn.reply.skipped")
          .addKeyValue("topic", record.topic())
          .addKeyValue("partition", record.partition())
          .addKeyValue("offset", record.offset())
          .addKeyValue("errorClass", rootClass(thrownException))
          .log("reply record skipped");
      return true;
    }

    @Override
    public void handleOtherException(
        Exception thrownException, Consumer<?, ?> consumer, MessageListenerContainer container, boolean batchListener) {
      log.atWarn()
          .addKeyValue("event", "txn.reply.consumer.error")
          .addKeyValue("errorClass", rootClass(thrownException))
          .log("reply consumer error");
    }

    private static String rootClass(Throwable e) {
      Throwable t = e;
      while (t.getCause() != null && t.getCause() != t) {
        t = t.getCause();
      }
      return t.getClass().getName();
    }
  }
}
