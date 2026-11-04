package mfs.mw.orchestrator.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import mfs.mw.orchestrator.config.ValkeyConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/** An unreachable Valkey never blocks or fails intake (ticket 50): the lock is just unavailable. */
class ValkeyIdempotencyLockTest {

  private LettuceConnectionFactory factory;

  @AfterEach
  void tearDown() {
    if (factory != null) {
      factory.destroy();
    }
  }

  private static int closedPort() throws IOException {
    try (ServerSocket s = new ServerSocket(0)) {
      return s.getLocalPort();
    }
  }

  @Test
  void anUnreachableServerIsReportedUnavailableWithinTheCommandTimeout() throws IOException {
    var clientConfig =
        LettuceClientConfiguration.builder()
            .commandTimeout(Duration.ofMillis(200))
            .clientOptions(ValkeyConfig.failFast(Duration.ofMillis(200)).build())
            .build();
    factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", closedPort()), clientConfig);
    factory.afterPropertiesSet();
    factory.start();
    ValkeyIdempotencyLock lock = new ValkeyIdempotencyLock(new StringRedisTemplate(factory));

    long start = System.nanoTime();
    IdempotencyLock.Result first = lock.tryAcquire("mw:orchestrator:idem:k", "v", Duration.ofSeconds(60));
    IdempotencyLock.Result second = lock.tryAcquire("mw:orchestrator:idem:k", "v", Duration.ofSeconds(60));
    Duration took = Duration.ofNanos(System.nanoTime() - start);

    assertThat(first).isInstanceOf(IdempotencyLock.Result.Unavailable.class);
    assertThat(second).isInstanceOf(IdempotencyLock.Result.Unavailable.class);
    assertThat(took).isLessThan(Duration.ofSeconds(3));
  }
}
