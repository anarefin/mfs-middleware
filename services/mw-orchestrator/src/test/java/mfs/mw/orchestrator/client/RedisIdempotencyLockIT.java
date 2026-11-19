package mfs.mw.orchestrator.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import mfs.mw.orchestrator.config.RedisConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The lock against a real Redis (spec 7.3 rule 4, ticket 50): {@code SET NX PX} takes it once,
 * a repeat sees the holder's value, and the key frees itself after the TTL.
 */
class RedisIdempotencyLockIT {

  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:8.10.3-alpine")).withExposedPorts(6379);

  private static LettuceConnectionFactory factory;
  private static StringRedisTemplate template;
  private static RedisIdempotencyLock lock;

  @BeforeAll
  static void start() {
    REDIS.start();
    var clientConfig =
        LettuceClientConfiguration.builder()
            .commandTimeout(Duration.ofSeconds(2))
            .clientOptions(RedisConfig.failFast(Duration.ofSeconds(2)).build())
            .build();
    factory =
        new LettuceConnectionFactory(
            new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)), clientConfig);
    factory.afterPropertiesSet();
    factory.start();
    template = new StringRedisTemplate(factory);
    lock = new RedisIdempotencyLock(template);
  }

  @AfterAll
  static void stop() {
    if (factory != null) {
      factory.destroy();
    }
    REDIS.stop();
  }

  private static String key() {
    return "mw:orchestrator:idem:" + UUID.randomUUID();
  }

  @Test
  void theFirstCallerAcquiresTheLockAndTheKeyCarriesItsValueAndTtl() {
    String key = key();

    IdempotencyLock.Result result = lock.tryAcquire(key, "txn-1", Duration.ofSeconds(60));

    assertThat(result).isInstanceOf(IdempotencyLock.Result.Acquired.class);
    assertThat(template.opsForValue().get(key)).isEqualTo("txn-1");
    assertThat(template.getExpire(key)).isBetween(1L, 60L);
  }

  @Test
  void aRepeatWhileTheLockIsHeldSeesTheHoldersValueAndDoesNotOverwriteIt() {
    String key = key();
    lock.tryAcquire(key, "txn-1", Duration.ofSeconds(60));

    IdempotencyLock.Result repeat = lock.tryAcquire(key, "txn-2", Duration.ofSeconds(60));

    assertThat(repeat).isEqualTo(new IdempotencyLock.Result.Held("txn-1"));
    assertThat(template.opsForValue().get(key)).isEqualTo("txn-1");
  }

  @Test
  void theLockFreesItselfAfterTheTtlSoTheNextCallerAcquiresIt() throws InterruptedException {
    String key = key();
    lock.tryAcquire(key, "txn-1", Duration.ofMillis(200));

    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (Boolean.TRUE.equals(template.hasKey(key)) && System.nanoTime() < deadline) {
      Thread.sleep(50);
    }

    assertThat(lock.tryAcquire(key, "txn-2", Duration.ofSeconds(60)))
        .isInstanceOf(IdempotencyLock.Result.Acquired.class);
    assertThat(template.opsForValue().get(key)).isEqualTo("txn-2");
  }
}
