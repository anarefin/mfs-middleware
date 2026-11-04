package mfs.mw.orchestrator.client;

import java.time.Duration;

/**
 * A short-lived lock on an idempotency key (spec 7.3 rule 4): Valkey {@code SET key value NX PX
 * ttl}. Never throws for an unreachable or slow server: that is {@link Result.Unavailable}.
 */
public interface IdempotencyLock {

  /** Takes the lock when nobody holds it, else reports the holder's value. */
  Result tryAcquire(String key, String value, Duration ttl);

  sealed interface Result {
    /** This call set the key. */
    record Acquired() implements Result {}

    /** Somebody else's value is set ({@code null} when it expired between the two calls). */
    record Held(String value) implements Result {}

    /** The lock could not be asked (server down, timeout). */
    record Unavailable(String reason) implements Result {}

    /** No lock is configured ({@code mw.orchestrator.idempotency-lock.enabled=false}): the primary key alone. */
    record Off() implements Result {}
  }
}
