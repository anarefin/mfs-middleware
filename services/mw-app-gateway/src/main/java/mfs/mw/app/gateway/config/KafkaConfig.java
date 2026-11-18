package mfs.mw.app.gateway.config;

import jakarta.validation.ValidationException;
import java.time.Clock;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;
import mfs.mw.app.gateway.messaging.ConsumerContextInterceptor;
import mfs.mw.app.gateway.messaging.RetryDueTimeCap;
import mfs.mw.app.gateway.messaging.RetryTopicNaming;
import mfs.mw.app.gateway.messaging.RetryTopicRecoverer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.config.ContainerCustomizer;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.CompositeRecordInterceptor;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.kafka.retrytopic.DeadLetterPublishingRecovererFactory;
import org.springframework.kafka.retrytopic.RetryTopicComponentFactory;
import org.springframework.kafka.retrytopic.RetryTopicConfiguration;
import org.springframework.kafka.retrytopic.RetryTopicConfigurationBuilder;
import org.springframework.kafka.retrytopic.RetryTopicConfigurationSupport;
import org.springframework.kafka.retrytopic.RetryTopicNamesProviderFactory;
import org.springframework.kafka.retrytopic.RetryTopicSchedulerWrapper;
import org.springframework.kafka.retrytopic.SameIntervalTopicReuseStrategy;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.BackOffExecution;
import tools.jackson.core.JacksonException;

/**
 * Spring Kafka for this service (spec 6.1, 19.2, ADR D27). Connection, serdes, producer
 * idempotence, ack mode, concurrency and observation are {@code spring.kafka.*} in {@code
 * application.yml}; this class adds what has no property:
 *
 * <ul>
 *   <li>Two {@link KafkaTemplate}s on Boot's one producer factory (one producer, so one send
 *       order): {@code kafkaTemplate}, observed (Micrometer writes the producer span as {@code
 *       traceparent}), for live publishes; {@code forwardingKafkaTemplate}, not observed, for
 *       records whose headers are already final (outbox rows, records parked on a retry topic or
 *       the DLQ), so their {@code traceparent} is kept as it is.
 *   <li>Non-blocking retry topics for every {@code @KafkaListener} on the default container
 *       factory (except {@code mw.kafka.retry.exclude-topics}): one retry topic per {@code
 *       mw.kafka.retry.delays} entry, then the DLQ, under the platform's names ({@link
 *       RetryTopicNaming}: {@code <topic>.<group>.retry.<n>}, {@code <topic>.<group>.dlq}). The
 *       topics are never created here; {@code deploy/kafka/topics.txt} declares them ({@code
 *       retry=<group>}). Validation and schema failures ({@link ValidationException}, {@link
 *       JacksonException}) and records that cannot be deserialized are not retried: they go to the
 *       DLQ at once. The DLQ is never consumed by the service itself (its container does not start).
 *   <li>The {@code mw-retry-*} headers and quiet, masked logging of each hop ({@link
 *       RetryTopicRecoverer}), the due-time cap of retry records ({@link RetryDueTimeCap}) and the
 *       consumer-side MDC ({@link ConsumerContextInterceptor}).
 *   <li>The listener containers' shutdown timeout ({@code mw.kafka.listener.shutdown-timeout}).
 * </ul>
 *
 * <p>Delays never block the consumer thread: Spring Kafka pauses the record's partition, seeks back
 * to it and resumes it from {@link #retryTopicSchedulerWrapper()} once it is due. Offsets are
 * committed per record (AckMode {@code MANUAL_IMMEDIATE}): by the listener after it handled the
 * record, or by the error handler once the record is parked on its next topic; a record that
 * cannot be parked is not committed and is delivered again.
 */
@Configuration(proxyBeanMethods = false)
public class KafkaConfig extends RetryTopicConfigurationSupport {

  private final KafkaRetryProperties retry;
  private final MwKafkaProperties kafka;
  private final Clock clock;
  private final String group;

  /**
   * @param group the service's consumer group ({@code spring.kafka.consumer.group-id}, the service
   *     name), the middle part of its retry and DLQ topic names; every listener uses it
   */
  public KafkaConfig(
      KafkaRetryProperties retry,
      MwKafkaProperties kafka,
      Clock clock,
      @Value("${spring.kafka.consumer.group-id}") String group) {
    this.retry = retry;
    this.kafka = kafka;
    this.clock = clock;
    this.group = group;
  }

  /** Live publishes: observed, so the producer span becomes the record's {@code traceparent}. */
  @Bean
  @Primary
  public KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> producerFactory) {
    KafkaTemplate<String, String> template = new KafkaTemplate<>(producerFactory);
    template.setObservationEnabled(true);
    return template;
  }

  /** Records whose headers are final (outbox rows, parked records): sent exactly as they are. */
  @Bean
  public KafkaTemplate<String, String> forwardingKafkaTemplate(ProducerFactory<String, String> producerFactory) {
    return new KafkaTemplate<>(producerFactory);
  }

  /**
   * Retry-topic components with the platform's topic names and the service clock. A bean, not an
   * override of {@code createComponentFactory()}: the support class calls that from a field
   * initialiser, before this class's fields are set.
   */
  @Bean
  public RetryTopicComponentFactory retryTopicComponentFactory() {
    RetryTopicNaming naming = new RetryTopicNaming(group);
    return new RetryTopicComponentFactory() {
      @Override
      public RetryTopicNamesProviderFactory retryTopicNamesProviderFactory() {
        return naming;
      }

      @Override
      public Clock internalRetryTopicClock() {
        return clock;
      }
    };
  }

  /** Resumes paused retry partitions; its own thread, apart from the {@code @Scheduled} jobs. */
  @Bean
  public RetryTopicSchedulerWrapper retryTopicSchedulerWrapper() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setThreadNamePrefix("kafka-retry-");
    return new RetryTopicSchedulerWrapper(scheduler);
  }

  /** Retry topics, then the DLQ, for every listener topic not excluded. */
  @Bean
  public RetryTopicConfiguration retryTopicConfiguration(
      @Qualifier("forwardingKafkaTemplate") KafkaTemplate<String, String> forwarding) {
    return RetryTopicConfigurationBuilder.newInstance()
        .excludeTopics(retry.excludeTopics())
        .customBackoff(delays(retry.delays()))
        .maxAttempts(retry.attempts() + 1) // the first delivery counts as an attempt
        // One topic per retry, named by its 1-based position (RetryTopicNaming), even when two
        // delays are equal (the dev stack's 1s,1s,1s).
        .suffixTopicsWithIndexValues()
        .sameIntervalTopicReuseStrategy(SameIntervalTopicReuseStrategy.MULTIPLE_TOPICS)
        .retryTopicSuffix(RetryTopicNaming.RETRY_SUFFIX)
        .dltSuffix(RetryTopicNaming.DLQ_SUFFIX)
        .doNotAutoCreateRetryTopics()
        .autoStartDltHandler(false)
        .create(forwarding);
  }

  /** Not retried: a message that fails validation or does not parse fails the same way again. */
  @Override
  protected void manageNonBlockingFatalExceptions(List<Class<? extends Throwable>> fatal) {
    fatal.add(ValidationException.class);
    fatal.add(JacksonException.class);
  }

  /**
   * Every hop goes through {@link RetryTopicRecoverer} (the {@code mw-*} headers, no exception
   * message or stack trace); the framework's own ERROR log of the failure is off, as it carries
   * the exception message.
   */
  @Override
  protected Consumer<DeadLetterPublishingRecovererFactory> configureDeadLetterPublishingContainerFactory() {
    return factory -> {
      factory.setDeadLetterPublisherCreator(
          (templates, destinations) -> new RetryTopicRecoverer(templates, destinations, retry, clock));
      factory.neverLogListenerException();
    };
  }

  /** Consumer-side MDC and the retry due-time cap, on every container of the default factory. */
  @Bean
  public RecordInterceptor<Object, Object> recordInterceptor() {
    return new CompositeRecordInterceptor<>(new ConsumerContextInterceptor(), new RetryDueTimeCap(retry, clock));
  }

  /** The listener containers' shutdown timeout, which has no Spring Boot property. */
  @Bean
  public ContainerCustomizer<Object, Object, ConcurrentMessageListenerContainer<Object, Object>> listenerShutdownTimeout() {
    long timeout = kafka.listener().shutdownTimeout().toMillis();
    return container -> container.getContainerProperties().setShutdownTimeout(timeout);
  }

  /** The configured delays, in order, as a Spring {@link BackOff}. */
  static BackOff delays(List<Duration> delays) {
    return () -> {
      Iterator<Duration> next = delays.iterator();
      return () -> next.hasNext() ? next.next().toMillis() : BackOffExecution.STOP;
    };
  }
}
