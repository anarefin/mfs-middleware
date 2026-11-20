package mfs.mw.app.gateway.repository;

import java.time.Duration;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * Previews waiting for their confirm (ticket 105): key {@code preview:<id>}, value {@code <wallet
 * scope hash>\n<txnType>\n<txn hash>} (no MSISDN, amount or payee in Redis), TTL the preview's
 * lifetime. Gone after the TTL: the confirm is refused as expired.
 */
@Repository
public class PreviewStore {

  /** A kept preview: whose it is (hash of channel and wallet), its type and the canonical txn hash. */
  public record Stored(String scope, String txnType, String txnHash) {}

  static final String PREFIX = "preview:";

  private final StringRedisTemplate redis;

  public PreviewStore(StringRedisTemplate redis) {
    this.redis = redis;
  }

  public void save(String id, Stored preview, Duration ttl) {
    redis.opsForValue().set(PREFIX + id, preview.scope() + "\n" + preview.txnType() + "\n" + preview.txnHash(), ttl);
  }

  public Optional<Stored> find(String id) {
    String v = redis.opsForValue().get(PREFIX + id);
    if (v == null) {
      return Optional.empty();
    }
    String[] parts = v.split("\n", -1);
    return parts.length == 3 ? Optional.of(new Stored(parts[0], parts[1], parts[2])) : Optional.empty();
  }
}
