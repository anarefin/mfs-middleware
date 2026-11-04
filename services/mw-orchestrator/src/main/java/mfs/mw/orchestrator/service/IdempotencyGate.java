package mfs.mw.orchestrator.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import mfs.mw.orchestrator.client.IdempotencyLock;
import mfs.mw.orchestrator.config.OrchestratorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * The 60 s double-tap lock in front of the PostgreSQL idempotency claim (spec 7.3 rule 4, M2 gate
 * decision, ticket 50). The first request of a scope (channel, initiator id, key) sets the lock to
 * {@code <txnId> <request hash>} and goes on to the claim; a second request of the same scope
 * while the lock is held is answered from the lock, without a claim attempt: same content is a
 * duplicate of the holder's txnId, other content a conflict. The primary key of
 * {@code txn.idempotency} stays the final guarantee: a redelivered record (same txnId), an
 * unreadable lock value and an unavailable Valkey all proceed to the claim. Valkey trouble never
 * blocks or fails a request; it is logged {@code txn.idempotency.lock_unavailable} and counted
 * ({@code mw.txn.idempotency.lock.unavailable}).
 */
@Service
public class IdempotencyGate {

  private static final Logger log = LoggerFactory.getLogger(IdempotencyGate.class);

  static final String KEY_PREFIX = "mw:orchestrator:idem:";
  private static final HexFormat HEX = HexFormat.of();

  /** What the intake does with a request. */
  public sealed interface Decision {
    /** Go on to the PostgreSQL claim. */
    record Proceed() implements Decision {}

    /** Same content as the lock holder: answer as a duplicate of {@code holderTxnId}. */
    record Duplicate(UUID holderTxnId) implements Decision {}

    /** Other content under a held key: {@code MW-IDEM-001}. */
    record Conflict() implements Decision {}
  }

  private final IdempotencyLock lock;
  private final Duration ttl;
  private final Counter unavailable;

  @Autowired
  public IdempotencyGate(IdempotencyLock lock, OrchestratorProperties properties, MeterRegistry meters) {
    this(lock, properties.idempotencyLock().ttl(), meters);
  }

  IdempotencyGate(IdempotencyLock lock, Duration ttl, MeterRegistry meters) {
    this.lock = lock;
    this.ttl = ttl;
    this.unavailable =
        Counter.builder("mw.txn.idempotency.lock.unavailable")
            .description("Requests that fell back to the PostgreSQL primary key because the Valkey lock was unavailable")
            .register(meters);
  }

  public Decision enter(String channel, String initiatorId, String idempotencyKey, UUID txnId, byte[] hash) {
    String hashHex = HEX.formatHex(hash);
    IdempotencyLock.Result result;
    try {
      result = lock.tryAcquire(key(channel, initiatorId, idempotencyKey), txnId + " " + hashHex, ttl);
    } catch (RuntimeException e) {
      result = new IdempotencyLock.Result.Unavailable(e.getClass().getSimpleName());
    }
    return switch (result) {
      case IdempotencyLock.Result.Acquired a -> new Decision.Proceed();
      case IdempotencyLock.Result.Off o -> new Decision.Proceed();
      case IdempotencyLock.Result.Unavailable u -> fallBack(txnId, u.reason());
      case IdempotencyLock.Result.Held h -> held(h.value(), txnId, hashHex);
    };
  }

  private Decision held(String value, UUID txnId, String hashHex) {
    String[] parts = value == null ? new String[0] : value.split(" ", 2);
    if (parts.length != 2) {
      return new Decision.Proceed();
    }
    UUID holder;
    try {
      holder = UUID.fromString(parts[0]);
    } catch (IllegalArgumentException e) {
      return new Decision.Proceed();
    }
    if (holder.equals(txnId)) {
      return new Decision.Proceed();
    }
    return parts[1].equals(hashHex) ? new Decision.Duplicate(holder) : new Decision.Conflict();
  }

  private Decision fallBack(UUID txnId, String reason) {
    unavailable.increment();
    log.atWarn()
        .addKeyValue("event", "txn.idempotency.lock_unavailable")
        .addKeyValue("txnId", txnId.toString())
        .addKeyValue("reason", reason)
        .log("idempotency lock unavailable; relying on the PostgreSQL primary key");
    return new Decision.Proceed();
  }

  /** The Valkey key of a scope: a SHA-256 of it, so no MSISDN or key text lands in Valkey. */
  String key(String channel, String initiatorId, String idempotencyKey) {
    try {
      MessageDigest sha = MessageDigest.getInstance("SHA-256");
      String scope = channel + "\u0000" + initiatorId + "\u0000" + idempotencyKey;
      return KEY_PREFIX + HEX.formatHex(sha.digest(scope.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }
}
