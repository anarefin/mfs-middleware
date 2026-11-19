package mfs.mw.app.gateway.repository;

import java.time.Duration;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * The first 200 response per idempotency scope in Valkey/Redis (FRS PLT-011, ticket 99): key
 * {@code idemresp:<sha256(channel, wallet, key)>}, value {@code <request hash hex>\n<response
 * JSON>}, set once ({@code SET NX EX}, so the first final response wins). Callers treat any
 * failure as "nothing stored": the orchestrator then answers the repeat as before.
 */
@Repository
public class IdempotentResponseStore {

  /** A stored response: the request hash it answered and the body as sent. */
  public record Stored(String requestHash, String body) {}

  static final String PREFIX = "idemresp:";

  private final StringRedisTemplate redis;

  public IdempotentResponseStore(StringRedisTemplate redis) {
    this.redis = redis;
  }

  public Optional<Stored> find(String scope) {
    String v = redis.opsForValue().get(PREFIX + scope);
    if (v == null) {
      return Optional.empty();
    }
    int nl = v.indexOf('\n');
    return nl < 0 ? Optional.empty() : Optional.of(new Stored(v.substring(0, nl), v.substring(nl + 1)));
  }

  /** Keeps the response unless one is already stored for the scope. */
  public void saveFirst(String scope, Stored stored, Duration ttl) {
    redis.opsForValue().setIfAbsent(PREFIX + scope, stored.requestHash() + "\n" + stored.body(), ttl);
  }
}
