package mfs.mw.app.gateway.client;

import java.time.Duration;
import java.util.function.Supplier;
import mfs.mw.app.gateway.exception.ErrorCodes;
import mfs.mw.app.gateway.exception.MwException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Sessions and the nonce cache in Valkey (spec 9.1, 18.6). Session key {@code
 * sess:{subject}:{deviceId}} with a sliding idle timeout; nonce key {@code nonce:{deviceId}:{nonce}}
 * set once ({@code SET NX PX}). Fails closed: Valkey not answering is 503 MW-SYS-012 (retryable),
 * never a request let through unchecked.
 */
@Component
public class DeviceSessionStore {

  private final StringRedisTemplate valkey;

  public DeviceSessionStore(StringRedisTemplate valkey) {
    this.valkey = valkey;
  }

  static String sessionKey(String subject, String deviceId) {
    return "sess:" + subject + ":" + deviceId;
  }

  public void open(String subject, String deviceId, Duration idle) {
    call(() -> {
      valkey.opsForValue().set(sessionKey(subject, deviceId), deviceId, idle);
      return null;
    });
  }

  /** Extends a live session by {@code idle}; false when there is none (expired or ended). */
  public boolean touch(String subject, String deviceId, Duration idle) {
    return Boolean.TRUE.equals(call(() -> valkey.expire(sessionKey(subject, deviceId), idle)));
  }

  public void close(String subject, String deviceId) {
    call(() -> valkey.delete(sessionKey(subject, deviceId)));
  }

  /** True the first time this device uses this nonce within {@code ttl}. */
  public boolean firstUse(String deviceId, String nonce, Duration ttl) {
    return Boolean.TRUE.equals(
        call(() -> valkey.opsForValue().setIfAbsent("nonce:" + deviceId + ":" + nonce, "1", ttl)));
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
