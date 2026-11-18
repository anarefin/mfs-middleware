package mfs.mw.adapter.billpay.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Kafka client settings Spring Boot has no property for (ADR D27); the rest is {@code
 * spring.kafka.*} in {@code application.yml}, the retry delays are {@link KafkaRetryProperties}.
 *
 * @param sendTimeout how long {@code EventPublisher.publish} waits for Kafka's acknowledgement
 * @param listener listener container settings
 */
@Validated
@ConfigurationProperties("mw.kafka")
public record MwKafkaProperties(@NotNull Duration sendTimeout, @Valid @NotNull Listener listener) {

  /**
   * @param shutdownTimeout how long a stopping listener container waits for its consumer thread
   *     (Spring Kafka's {@code ContainerProperties.shutdownTimeout}); short in tests
   */
  public record Listener(@NotNull Duration shutdownTimeout) {}
}
