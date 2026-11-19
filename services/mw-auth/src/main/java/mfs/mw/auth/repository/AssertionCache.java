package mfs.mw.auth.repository;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;
import mfs.mw.auth.exception.ErrorCodes;
import mfs.mw.auth.exception.MwException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;

/**
 * The assertions issued within their life, per wallet and idempotency key (spec 18.7, D22; ticket
 * 71): Redis key {@code assert:{walletId}:{idempotencyKey}}, TTL = the life, value {@code
 * <expiresAt epoch ms>|<txnHash>|<newDevice>|<assertion>}. A channel's retry gets the same assertion
 * without a second PIN check. Set once ({@code SET NX}): of two concurrent first calls the later
 * one gets the winner's entry. Redis down is 503 MW-SYS-012 (fail closed).
 */
@Repository
public class AssertionCache {

  /** One cached assertion. */
  public record Entry(Instant expiresAt, String txnHash, boolean newDevice, String assertion) {
    @Override
    public String toString() {
      return "Entry[expiresAt=" + expiresAt + ", txnHash=" + txnHash + ", newDevice=" + newDevice + ", assertion=****]";
    }

    String encode() {
      return expiresAt.toEpochMilli() + "|" + txnHash + "|" + newDevice + "|" + assertion;
    }

    static Entry decode(String v) {
      String[] p = v.split("\\|", 4);
      return new Entry(Instant.ofEpochMilli(Long.parseLong(p[0])), p[1], Boolean.parseBoolean(p[2]), p[3]);
    }
  }

  private final StringRedisTemplate redis;

  public AssertionCache(StringRedisTemplate redis) {
    this.redis = redis;
  }

  static String key(String walletId, String idempotencyKey) {
    return "assert:" + walletId + ":" + idempotencyKey;
  }

  public Optional<Entry> find(String walletId, String idempotencyKey) {
    String v = call(() -> redis.opsForValue().get(key(walletId, idempotencyKey)));
    return v == null ? Optional.empty() : Optional.of(Entry.decode(v));
  }

  /**
   * Stores {@code entry} unless one is there; returns what is stored afterwards (this one, or the
   * one a concurrent call stored first).
   */
  public Entry putIfAbsent(String walletId, String idempotencyKey, Entry entry, Duration ttl) {
    String k = key(walletId, idempotencyKey);
    return call(() -> {
      if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(k, entry.encode(), ttl))) {
        return entry;
      }
      String v = redis.opsForValue().get(k);
      return v == null ? entry : Entry.decode(v);
    });
  }

  /** Replaces an entry whose life ended by the service clock (Redis's TTL may lag behind it). */
  public void replace(String walletId, String idempotencyKey, Entry entry, Duration ttl) {
    call(() -> {
      redis.opsForValue().set(key(walletId, idempotencyKey), entry.encode(), ttl);
      return null;
    });
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
