package mfs.mw.orchestrator.client;

import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * {@link IdempotencyLock} on Valkey: {@code SET key value NX PX ttl}, then {@code GET} for the
 * holder when the key was already set. Any failure (connection refused, timeout, rejected while
 * disconnected) is {@link Result.Unavailable}; the caller falls back to the primary key. The key
 * is never released early: it expires after {@code ttl}, and until then a repeated request is
 * answered from it exactly as the primary key would answer it.
 */
public class ValkeyIdempotencyLock implements IdempotencyLock {

  private final StringRedisTemplate valkey;

  public ValkeyIdempotencyLock(StringRedisTemplate valkey) {
    this.valkey = valkey;
  }

  @Override
  public Result tryAcquire(String key, String value, Duration ttl) {
    try {
      Boolean set = valkey.opsForValue().setIfAbsent(key, value, ttl);
      if (Boolean.TRUE.equals(set)) {
        return new Result.Acquired();
      }
      return new Result.Held(valkey.opsForValue().get(key));
    } catch (RuntimeException e) {
      return new Result.Unavailable(e.getClass().getSimpleName());
    }
  }
}
