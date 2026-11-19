package mfs.mw.app.gateway.repository;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;
import mfs.mw.app.gateway.exception.ErrorCodes;
import mfs.mw.app.gateway.exception.MwException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;

/**
 * The device gate's Redis keys (spec 9.1, 18.6; ticket 68, the device data itself is mw-auth's):
 *
 * <ul>
 *   <li>{@code devkey:{deviceId}}: the bound device's wallet and public key as mw-auth answered
 *       them, kept {@code device-key-ttl} (5 min);
 *   <li>{@code sesschk:{walletId}:{deviceId}}: mw-auth confirmed a live session, kept {@code
 *       session-cache-ttl} (10 s), so a burst of requests asks mw-auth once; the value is the
 *       fingerprint of the device key mw-auth answered with (ticket 69), so a cached key of a
 *       replaced binding is never confirmed;
 *   <li>{@code nonce:{deviceId}:{nonce}}: a signed request's nonce, set once ({@code SET NX PX}).
 *       mw-auth keeps its nonces under the same key in the same Redis, so a nonce is used once
 *       per device across both services.
 * </ul>
 *
 * Fails closed: Redis not answering is 503 MW-SYS-012 (retryable), never a request let through.
 */
@Repository
public class DeviceGateStore {

  /** A cached bound device: its wallet and public key. */
  public record CachedKey(String walletId, String publicKey) {}

  private final StringRedisTemplate redis;

  public DeviceGateStore(StringRedisTemplate redis) {
    this.redis = redis;
  }

  public Optional<CachedKey> deviceKey(String deviceId) {
    String v = call(() -> redis.opsForValue().get("devkey:" + deviceId));
    if (v == null) {
      return Optional.empty();
    }
    int nl = v.indexOf('\n');
    return nl < 0 ? Optional.empty() : Optional.of(new CachedKey(v.substring(0, nl), v.substring(nl + 1)));
  }

  public void putDeviceKey(String deviceId, CachedKey key, Duration ttl) {
    if (ttl.isPositive()) {
      call(() -> {
        redis.opsForValue().set("devkey:" + deviceId, key.walletId() + "\n" + key.publicKey(), ttl);
        return null;
      });
    }
  }

  /** Drops a cached device (its binding changed at mw-auth). */
  public void evictDeviceKey(String deviceId) {
    call(() -> redis.delete("devkey:" + deviceId));
  }

  /** The key fingerprint of a recent session confirmation, empty when none is cached. */
  public Optional<String> sessionConfirmed(String walletId, String deviceId) {
    return Optional.ofNullable(call(() -> redis.opsForValue().get(sessionKey(walletId, deviceId))));
  }

  public void confirmSession(String walletId, String deviceId, String fingerprint, Duration ttl) {
    if (ttl.isPositive()) {
      call(() -> {
        redis.opsForValue().set(sessionKey(walletId, deviceId), fingerprint, ttl);
        return null;
      });
    }
  }

  /** True the first time this device uses this nonce within {@code ttl}. */
  public boolean firstUse(String deviceId, String nonce, Duration ttl) {
    return Boolean.TRUE.equals(
        call(() -> redis.opsForValue().setIfAbsent("nonce:" + deviceId + ":" + nonce, "1", ttl)));
  }

  private static String sessionKey(String walletId, String deviceId) {
    return "sesschk:" + walletId + ":" + deviceId;
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
