package mfs.mw.orchestrator.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import mfs.mw.orchestrator.TestData;
import mfs.mw.orchestrator.TestInfrastructure;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code txn.idempotency} (spec 7.3: claim, repeat, expired key reused) and {@code txn.saga_timer}
 * (7.1: arm, retry count, explicit attempt, due order, {@code FOR UPDATE SKIP LOCKED}) against
 * PostgreSQL. One cached context for both repository ITs.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {"mw.orchestrator.consumers.enabled=false", "mw.orchestrator.timer.enabled=false"})
@Import(TestInfrastructure.class)
class IdempotencyAndTimerRepositoryIT {

  private static final Instant T0 = TestData.NOW;
  private static final byte[] HASH_A = "hash-a".getBytes(StandardCharsets.UTF_8);
  private static final byte[] HASH_B = "hash-b".getBytes(StandardCharsets.UTF_8);

  private final IdempotencyRepository keys;
  private final SagaTimerRepository timers;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;

  @Autowired
  IdempotencyAndTimerRepositoryIT(
      IdempotencyRepository keys, SagaTimerRepository timers, JdbcTemplate jdbc, TransactionTemplate tx) {
    this.keys = keys;
    this.timers = timers;
    this.jdbc = jdbc;
    this.tx = tx;
  }

  @BeforeEach
  void clean() {
    for (String t : List.of("saga_timer", "idempotency")) {
      jdbc.update("delete from " + t);
    }
  }

  // --- idempotency ---------------------------------------------------------------------------

  @Test
  void theFirstClaimWinsAndARepeatGetsTheStoredTxnAndHash() {
    UUID first = UUID.randomUUID();
    assertThat(keys.claim("CUSTOMER_APP", "01711000001", "k-1", HASH_A, first, T0, T0.plus(Duration.ofHours(72))))
        .isEmpty();

    Optional<IdempotencyRepository.Stored> repeat =
        keys.claim("CUSTOMER_APP", "01711000001", "k-1", HASH_B, UUID.randomUUID(), T0, T0.plus(Duration.ofHours(72)));

    assertThat(repeat).isPresent();
    assertThat(repeat.get().txnId()).isEqualTo(first);
    assertThat(repeat.get().sameRequest(HASH_A)).isTrue();
    assertThat(repeat.get().sameRequest(HASH_B)).isFalse();
  }

  @Test
  void theScopeIsChannelInitiatorAndKey() {
    Instant exp = T0.plus(Duration.ofHours(72));
    assertThat(keys.claim("CUSTOMER_APP", "01711000001", "k-2", HASH_A, UUID.randomUUID(), T0, exp)).isEmpty();
    assertThat(keys.claim("AGENT_APP", "01711000001", "k-2", HASH_A, UUID.randomUUID(), T0, exp)).isEmpty();
    assertThat(keys.claim("CUSTOMER_APP", "01711000002", "k-2", HASH_A, UUID.randomUUID(), T0, exp)).isEmpty();
  }

  @Test
  void anExpiredKeyIsClaimedAgainInPlace() {
    keys.claim("CUSTOMER_APP", "01711000001", "k-3", HASH_A, UUID.randomUUID(), T0, T0.plusSeconds(60));
    UUID later = UUID.randomUUID();
    Instant now = T0.plusSeconds(61);

    assertThat(keys.claim("CUSTOMER_APP", "01711000001", "k-3", HASH_B, later, now, now.plus(Duration.ofHours(72))))
        .isEmpty();
    assertThat(keys.claim("CUSTOMER_APP", "01711000001", "k-3", HASH_A, UUID.randomUUID(), now, now.plusSeconds(1)))
        .map(IdempotencyRepository.Stored::txnId)
        .contains(later);
    assertThat(jdbc.queryForObject("select count(*) from idempotency", Integer.class)).isEqualTo(1);
  }

  // --- saga timers ---------------------------------------------------------------------------

  /** Locks the due timers in a transaction of its own (the locks end with it). */
  private List<SagaTimerRepository.Due> due(Instant now, int limit) {
    List<SagaTimerRepository.Due> due = tx.execute(s -> timers.lockDue(now, limit));
    return due == null ? List.of() : due;
  }

  @Test
  void armingAgainReplacesTheDeadlineAndARetryCountsTheAttempt() {
    UUID txn = UUID.randomUUID();
    timers.arm(txn, "COMMITTING", T0.plusSeconds(5), false);
    timers.arm(txn, "COMMITTING", T0.plusSeconds(10), true);
    timers.arm(txn, "COMMITTING", T0.plusSeconds(15), true);

    List<SagaTimerRepository.Due> due = due(T0.plusSeconds(15), 10);
    assertThat(due).singleElement().satisfies(d -> {
      assertThat(d.step()).isEqualTo("COMMITTING");
      assertThat(d.attempt()).isEqualTo(2);
      assertThat(d.dueAt()).isEqualTo(T0.plusSeconds(15));
    });

    timers.arm(txn, "COMMITTING", T0.plusSeconds(20), false);
    assertThat(due(T0.plusSeconds(20), 10)).singleElement()
        .satisfies(d -> assertThat(d.attempt()).isZero());
  }

  @Test
  void onlyDueTimersAreLockedOldestFirstUpToTheLimit() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    UUID c = UUID.randomUUID();
    timers.armAttempt(a, "UNKNOWN", T0.plusSeconds(3), 4);
    timers.arm(b, "VALIDATING", T0.plusSeconds(1), false);
    timers.arm(c, "POSTING", T0.plusSeconds(30), false);

    List<SagaTimerRepository.Due> due = due(T0.plusSeconds(10), 10);
    assertThat(due).extracting(SagaTimerRepository.Due::txnId).containsExactly(b, a);
    assertThat(due.get(1).attempt()).isEqualTo(4);
    assertThat(due(T0.plusSeconds(10), 1)).extracting(SagaTimerRepository.Due::txnId)
        .containsExactly(b);
  }

  @Test
  void aSecondSweeperSkipsTimersTheFirstHolds() throws Exception {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    timers.arm(a, "VALIDATING", T0, false);
    timers.arm(b, "VALIDATING", T0.plusSeconds(1), false);
    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CompletableFuture<Void> first =
        CompletableFuture.runAsync(
            () ->
                tx.executeWithoutResult(
                    s -> {
                      assertThat(timers.lockDue(T0.plusSeconds(5), 1)).extracting(SagaTimerRepository.Due::txnId)
                          .containsExactly(a);
                      locked.countDown();
                      try {
                        release.await(10, TimeUnit.SECONDS);
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                      }
                    }));
    assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

    assertThat(due(T0.plusSeconds(5), 10)).extracting(SagaTimerRepository.Due::txnId)
        .containsExactly(b);

    release.countDown();
    first.get(10, TimeUnit.SECONDS);
  }

  @Test
  void deleteRemovesOneStepAndClearRemovesEveryStepOfATransaction() {
    UUID txn = UUID.randomUUID();
    timers.arm(txn, "COMMITTING", T0, false);
    timers.arm(txn, "UNKNOWN", T0, false);

    timers.delete(txn, "COMMITTING");
    assertThat(due(T0, 10)).extracting(SagaTimerRepository.Due::step).containsExactly("UNKNOWN");

    timers.clear(txn);
    assertThat(due(T0, 10)).isEmpty();
  }
}
