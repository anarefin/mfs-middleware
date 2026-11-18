package mfs.mw.portal.api.config;

import jakarta.validation.constraints.NotEmpty;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Non-blocking retry topics for Kafka consumer errors (spec 19.2, ADR D27): one {@code
 * <topic>.<group>.retry.<n>} per delay, then {@code <topic>.<group>.dlq}. Defaults 10 s, 1 min,
 * 10 min ({@code application.yml}); the dev stack shortens them ({@code MW_KAFKA_RETRY_DELAYS}).
 *
 * @param delays the delay before retry 1, 2, ...; their number is the number of retry topics
 * @param excludeTopics consumed topics that get no retry topics (a listener on such a topic
 *     handles its own failures); empty by default: every {@code @KafkaListener} topic is retried
 */
@Validated
@ConfigurationProperties("mw.kafka.retry")
public record KafkaRetryProperties(@NotEmpty List<Duration> delays, List<String> excludeTopics) {

  public KafkaRetryProperties {
    delays = List.copyOf(delays);
    excludeTopics = excludeTopics == null ? List.of() : List.copyOf(excludeTopics);
  }

  /** Delay before retry {@code n} (1-based). */
  public Duration delay(int n) {
    return delays.get(n - 1);
  }

  public int attempts() {
    return delays.size();
  }
}
