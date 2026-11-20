package mfs.mw.app.gateway.repository;

import java.time.Duration;
import mfs.mw.app.gateway.exception.ErrorCodes;
import mfs.mw.app.gateway.exception.MwException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;

/**
 * Bank account validation attempts per wallet (M9 review, FRS FL-08): Redis {@code avl:{walletId}},
 * a fixed window counter ({@code INCR}, the window's expiry set by the first attempt). Every link
 * attempt counts, a refused one too, so AVL cannot be used to enumerate accounts and holders. Fails
 * closed: Redis not answering is 503 MW-SYS-012, never an attempt let through.
 */
@Repository
public class AvlAttemptStore {

  private final StringRedisTemplate redis;

  public AvlAttemptStore(StringRedisTemplate redis) {
    this.redis = redis;
  }

  /** Counts one attempt of {@code walletId}; true while the window holds at most {@code limit}. */
  public boolean tryAcquire(String walletId, int limit, Duration window) {
    String key = "avl:" + walletId;
    try {
      Long n = redis.opsForValue().increment(key);
      if (n != null && n == 1L) {
        redis.expire(key, window);
      } else if (n != null && redis.getExpire(key) < 0) {
        redis.expire(key, window); // a counter left without its expiry never locks a wallet for good
      }
      return n != null && n <= limit;
    } catch (RuntimeException e) {
      throw new MwException(
              ErrorCodes.SYS_SESSION_STORE_UNAVAILABLE, "Service temporarily unavailable, try again", e)
          .withStatus(HttpStatus.SERVICE_UNAVAILABLE)
          .withRetryable(true);
    }
  }
}
