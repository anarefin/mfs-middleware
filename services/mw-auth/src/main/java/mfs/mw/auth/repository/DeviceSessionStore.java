package mfs.mw.auth.repository;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import mfs.mw.auth.exception.ErrorCodes;
import mfs.mw.auth.exception.MwException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;

/**
 * Sessions and the nonce cache in Redis (spec 9.1, 18.6, 18.7; moved from mw-app-gateway in
 * ticket 68). Session key {@code sess:{walletId}:{deviceId}} holding its idle timeout in seconds,
 * with that sliding TTL; nonce key {@code nonce:{deviceId}:{nonce}} set once ({@code SET NX PX}).
 * Fails closed: Redis not answering is 503 MW-SYS-012 (retryable), never a request let through
 * unchecked.
 */
@Repository
public class DeviceSessionStore {

  private final StringRedisTemplate redis;

  public DeviceSessionStore(StringRedisTemplate redis) {
    this.redis = redis;
  }

  static String sessionKey(String walletId, String deviceId) {
    return "sess:" + walletId + ":" + deviceId;
  }

  static String indexKey(String walletId) {
    return "sessidx:" + walletId;
  }

  public void open(String walletId, String deviceId, Duration idle) {
    call(() -> {
      redis.opsForValue().set(sessionKey(walletId, deviceId), Long.toString(idle.toSeconds()), idle);
      return null;
    });
  }

  /**
   * Opens the session and, when the wallet already has {@code maxSessions} live sessions, ends the oldest
   * ones. Returns the device ids whose sessions were ended (never the one just opened).
   */
  public List<String> admit(String walletId, String deviceId, Duration idle, int maxSessions, Instant now) {
    return call(() -> {
      redis.opsForValue().set(sessionKey(walletId, deviceId), Long.toString(idle.toSeconds()), idle);
      String idx = indexKey(walletId);
      redis.opsForZSet().add(idx, deviceId, now.toEpochMilli());
      Set<String> members = redis.opsForZSet().range(idx, 0, -1);
      if (members != null) {
        for (String member : members) {
          if (!Boolean.TRUE.equals(redis.hasKey(sessionKey(walletId, member)))) {
            redis.opsForZSet().remove(idx, member);
          }
        }
      }
      List<String> ended = new ArrayList<>();
      Long size = redis.opsForZSet().zCard(idx);
      while (size != null && size > maxSessions) {
        Set<String> oldest = redis.opsForZSet().range(idx, 0, 0);
        if (oldest == null || oldest.isEmpty()) {
          break;
        }
        String old = oldest.iterator().next();
        if (old.equals(deviceId)) {
          break;
        }
        redis.opsForZSet().remove(idx, old);
        redis.delete(sessionKey(walletId, old));
        ended.add(old);
        size = redis.opsForZSet().zCard(idx);
      }
      return ended;
    });
  }

  /**
   * Extends a live session by the idle timeout it was opened with and returns that timeout; null
   * when there is none (expired or ended).
   */
  public Duration touch(String walletId, String deviceId) {
    return call(() -> {
      String key = sessionKey(walletId, deviceId);
      String idle = redis.opsForValue().get(key);
      if (idle == null) {
        return null;
      }
      Duration d = Duration.ofSeconds(Long.parseLong(idle));
      return Boolean.TRUE.equals(redis.expire(key, d)) ? d : null;
    });
  }

  public void close(String walletId, String deviceId) {
    call(() -> {
      redis.delete(sessionKey(walletId, deviceId));
      redis.opsForZSet().remove(indexKey(walletId), deviceId);
      return null;
    });
  }

  /** True the first time this device uses this nonce within {@code ttl}. */
  public boolean firstUse(String deviceId, String nonce, Duration ttl) {
    return Boolean.TRUE.equals(
        call(() -> redis.opsForValue().setIfAbsent("nonce:" + deviceId + ":" + nonce, "1", ttl)));
  }

  private static <T> T call(Supplier<T> op) {
    try {
      return op.get();
    } catch (RuntimeException e) {
      throw new MwException(
              ErrorCodes.SYS_SESSION_STORE_UNAVAILABLE, "Service temporarily unavailable, try again", e)
          .withStatus(HttpStatus.SERVICE_UNAVAILABLE);
    }
  }
}
