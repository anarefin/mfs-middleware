package mfs.mw.app.gateway.repository;

import java.time.Duration;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * Previews waiting for their confirm (ticket 105): key {@code preview:<id>}, value {@code <wallet
 * scope hash>\n<txnType>\n<txn hash>\n<outstanding poisha>\n<minimum poisha>} (no MSISDN or payee;
 * the last two lines are the postpaid bill the quote stored, empty when there is none). TTL is the
 * preview's lifetime. Gone after the TTL: the confirm is refused as expired.
 */
@Repository
public class PreviewStore {

  /**
   * A kept preview: whose it is (hash of channel and wallet), its type, the canonical txn hash, and
   * the postpaid bill in poisha when the quote fetched one.
   */
  public record Stored(String scope, String txnType, String txnHash, Long outstandingMinor, Long minimumMinor) {

    /** A preview with no postpaid bill. */
    public Stored(String scope, String txnType, String txnHash) {
      this(scope, txnType, txnHash, null, null);
    }
  }

  static final String PREFIX = "preview:";

  private final StringRedisTemplate redis;

  public PreviewStore(StringRedisTemplate redis) {
    this.redis = redis;
  }

  public void save(String id, Stored preview, Duration ttl) {
    String outstanding = preview.outstandingMinor() == null ? "" : preview.outstandingMinor().toString();
    String minimum = preview.minimumMinor() == null ? "" : preview.minimumMinor().toString();
    redis.opsForValue().set(
        PREFIX + id,
        preview.scope() + "\n" + preview.txnType() + "\n" + preview.txnHash() + "\n" + outstanding + "\n" + minimum,
        ttl);
  }

  public Optional<Stored> find(String id) {
    String v = redis.opsForValue().get(PREFIX + id);
    if (v == null) {
      return Optional.empty();
    }
    String[] parts = v.split("\n", -1);
    if (parts.length == 3) {
      return Optional.of(new Stored(parts[0], parts[1], parts[2]));
    }
    if (parts.length != 5) {
      return Optional.empty();
    }
    return Optional.of(new Stored(parts[0], parts[1], parts[2], poisha(parts[3]), poisha(parts[4])));
  }

  private static Long poisha(String raw) {
    if (raw == null || raw.isEmpty()) {
      return null;
    }
    return Long.valueOf(raw);
  }
}
