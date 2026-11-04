package mfs.mw.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import mfs.mw.orchestrator.client.IdempotencyLock;
import mfs.mw.orchestrator.service.IdempotencyGate.Decision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The 60 s Valkey lock in front of the PostgreSQL claim (spec 7.3 rule 4, ticket 50). */
class IdempotencyGateTest {

  private static final UUID FIRST = UUID.fromString("0190a7c4-1b2e-7c3d-8e4f-5a6b7c8d9e01");
  private static final UUID SECOND = UUID.fromString("0190a7c4-1b2e-7c3d-8e4f-5a6b7c8d9e02");
  private static final byte[] HASH = {1, 2, 3};
  private static final byte[] OTHER_HASH = {9, 9, 9};

  /** In-memory stand-in for Valkey's SET NX PX; can be switched off like an unreachable server. */
  static final class FakeLock implements IdempotencyLock {
    final Map<String, String> values = new HashMap<>();
    final Map<String, Duration> ttls = new HashMap<>();
    boolean down;
    RuntimeException failure;
    int calls;

    @Override
    public Result tryAcquire(String key, String value, Duration ttl) {
      calls++;
      if (failure != null) {
        throw failure;
      }
      if (down) {
        return new Result.Unavailable("RedisConnectionFailureException");
      }
      String held = values.putIfAbsent(key, value);
      if (held != null) {
        return new Result.Held(held);
      }
      ttls.put(key, ttl);
      return new Result.Acquired();
    }
  }

  private FakeLock lock;
  private SimpleMeterRegistry meters;
  private IdempotencyGate gate;

  @BeforeEach
  void setUp() {
    lock = new FakeLock();
    meters = new SimpleMeterRegistry();
    gate = new IdempotencyGate(lock, Duration.ofSeconds(60), meters);
  }

  private Decision enter(UUID txnId, byte[] hash) {
    return gate.enter("APP", "01711000001", "key-1", txnId, hash);
  }

  @Test
  void theFirstRequestTakesTheLockForSixtySecondsAndProceedsToTheClaim() {
    assertThat(enter(FIRST, HASH)).isEqualTo(new Decision.Proceed());
    assertThat(lock.ttls.values()).containsExactly(Duration.ofSeconds(60));
  }

  @Test
  void theLockKeyHoldsNoPersonalData() {
    enter(FIRST, HASH);
    assertThat(lock.values.keySet())
        .singleElement()
        .satisfies(k -> assertThat(k).startsWith("mw:orchestrator:idem:").doesNotContain("01711000001", "key-1"));
  }

  @Test
  void aConcurrentSecondFirstRequestWithTheSameContentIsADuplicateOfTheLockHolder() {
    enter(FIRST, HASH);
    assertThat(enter(SECOND, HASH)).isEqualTo(new Decision.Duplicate(FIRST));
  }

  @Test
  void aConcurrentRequestWithOtherContentUnderTheSameKeyIsAConflict() {
    enter(FIRST, HASH);
    assertThat(enter(SECOND, OTHER_HASH)).isEqualTo(new Decision.Conflict());
  }

  @Test
  void anotherScopeIsNotBlocked() {
    enter(FIRST, HASH);
    assertThat(gate.enter("APP", "01711000002", "key-1", SECOND, HASH)).isEqualTo(new Decision.Proceed());
    assertThat(gate.enter("USSD", "01711000001", "key-1", SECOND, HASH)).isEqualTo(new Decision.Proceed());
  }

  /** A redelivered record (same txnId) must still reach the claim, or a crash would lose it. */
  @Test
  void theSameRequestRedeliveredWhileItsLockIsHeldProceedsToTheClaim() {
    enter(FIRST, HASH);
    assertThat(enter(FIRST, HASH)).isEqualTo(new Decision.Proceed());
  }

  @Test
  void whenValkeyIsUnreachableTheRequestFallsBackToThePrimaryKeyAndTheFallbackIsCounted() {
    lock.down = true;
    assertThat(enter(FIRST, HASH)).isEqualTo(new Decision.Proceed());
    assertThat(enter(SECOND, HASH)).isEqualTo(new Decision.Proceed());
    assertThat(meters.get("mw.txn.idempotency.lock.unavailable").counter().count()).isEqualTo(2.0);
  }

  @Test
  void anUnexpectedLockFailureNeverFailsTheRequest() {
    lock.failure = new IllegalStateException("boom");
    assertThat(enter(FIRST, HASH)).isEqualTo(new Decision.Proceed());
    assertThat(meters.get("mw.txn.idempotency.lock.unavailable").counter().count()).isEqualTo(1.0);
  }

  @Test
  void aLockValueThatCannotBeReadIsLeftToThePrimaryKey() {
    lock.values.put(gate.key("APP", "01711000001", "key-1"), "not a lock value");
    assertThat(enter(SECOND, HASH)).isEqualTo(new Decision.Proceed());
  }
}
