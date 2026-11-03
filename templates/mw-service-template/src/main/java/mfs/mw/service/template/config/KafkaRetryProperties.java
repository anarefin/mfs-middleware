package mfs.mw.service.template.config;

import jakarta.validation.constraints.NotEmpty;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Non-blocking retry topics for Kafka consumer errors (spec 19.2): one {@code <topic>.retry.<n>}
 * per delay, then {@code <topic>.dlq}. Defaults 10 s, 1 min, 10 min ({@code application.yml}).
 */
@Validated
@ConfigurationProperties("mw.kafka.retry")
public record KafkaRetryProperties(@NotEmpty List<Duration> delays) {

  public KafkaRetryProperties {
    delays = List.copyOf(delays);
  }

  /** Delay before retry {@code n} (1-based). */
  public Duration delay(int n) {
    return delays.get(n - 1);
  }

  public int attempts() {
    return delays.size();
  }
}
