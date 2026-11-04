package mfs.mw.orchestrator.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import java.time.Duration;
import mfs.mw.orchestrator.client.IdempotencyLock;
import mfs.mw.orchestrator.client.ValkeyIdempotencyLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientOptionsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Valkey for the idempotency lock (spec 7.3 rule 4, ticket 50). Connection settings are
 * {@code spring.data.redis.*} (password from OpenBao); short timeouts and rejecting commands while
 * disconnected keep a Valkey outage from slowing intake down: the lock is then unavailable and the
 * PostgreSQL primary key decides alone. Valkey is not part of the health groups
 * ({@code management.health.redis.enabled: false}), so an outage never takes the service out of
 * readiness.
 */
@Configuration(proxyBeanMethods = false)
public class ValkeyConfig {

  /** Fail at once while disconnected instead of queueing commands until they time out. */
  public static ClientOptions.Builder failFast(ClientOptions.Builder builder) {
    return builder.disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS);
  }

  /** Fail-fast client options with a connect timeout (for a hand-built connection factory). */
  public static ClientOptions.Builder failFast(Duration connectTimeout) {
    return failFast(ClientOptions.builder().socketOptions(SocketOptions.builder().connectTimeout(connectTimeout).build()));
  }

  @Bean
  LettuceClientOptionsBuilderCustomizer valkeyFailFast() {
    return ValkeyConfig::failFast;
  }

  @Bean
  @ConditionalOnProperty(name = "mw.orchestrator.idempotency-lock.enabled", havingValue = "true", matchIfMissing = true)
  IdempotencyLock valkeyIdempotencyLock(StringRedisTemplate valkey) {
    return new ValkeyIdempotencyLock(valkey);
  }

  @Bean
  @ConditionalOnProperty(name = "mw.orchestrator.idempotency-lock.enabled", havingValue = "false")
  IdempotencyLock noIdempotencyLock() {
    return (key, value, ttl) -> new IdempotencyLock.Result.Off();
  }
}
