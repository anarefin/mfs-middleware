package mfs.mw.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import mfs.mw.auth.exception.MwException;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

/** The device API fails closed: Redis not answering is 503 MW-SYS-012, never a pass. */
class DeviceSessionStoreTest {

  @Test
  void redisDownIs503AndRetryable() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    when(redis.opsForValue()).thenThrow(new RedisConnectionFailureException("down"));
    when(redis.expire(any(String.class), any(Duration.class))).thenThrow(new RedisConnectionFailureException("down"));
    DeviceSessionStore store = new DeviceSessionStore(redis);

    for (Runnable call :
        new Runnable[] {
          () -> store.firstUse("dev-00000001", "nonce-000000000001", Duration.ofMinutes(10)),
          () -> store.touch("01711000001", "dev-00000001")
        }) {
      assertThatThrownBy(call::run)
          .isInstanceOfSatisfying(MwException.class, e -> {
            assertThat(e.code()).isEqualTo("MW-SYS-012");
            assertThat(e.status().value()).isEqualTo(503);
            assertThat(e.retryable()).isTrue();
          });
    }
  }
}
