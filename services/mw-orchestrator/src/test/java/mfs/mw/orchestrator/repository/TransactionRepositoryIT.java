package mfs.mw.orchestrator.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import mfs.mw.orchestrator.dto.ErrorInfo;
import mfs.mw.orchestrator.dto.TxnRequest;
import mfs.mw.orchestrator.entity.TransactionRecord;
import mfs.mw.orchestrator.service.saga.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code txn.transaction} and {@code txn.saga_step} against PostgreSQL (Testcontainers, the
 * module's JVM-scoped {@link TestInfrastructure}): round trip of every column the saga writes,
 * the row lock of {@code findForUpdate}, the unique {@code reversal_txn_id}, step history and the
 * backlog queries.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {"mw.orchestrator.consumers.enabled=false", "mw.orchestrator.timer.enabled=false"})
@Import(TestInfrastructure.class)
class TransactionRepositoryIT {

  private static final Instant T0 = TestData.NOW;

  private final TransactionRepository repo;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;

  @Autowired
  TransactionRepositoryIT(TransactionRepository repo, JdbcTemplate jdbc, TransactionTemplate tx) {
    this.repo = repo;
    this.jdbc = jdbc;
    this.tx = tx;
  }

  @BeforeEach
  void clean() {
    for (String t : List.of("outbox", "inbox", "saga_timer", "saga_step", "idempotency", "transaction")) {
      jdbc.update("delete from " + t);
    }
  }

  private static TransactionRecord received(TxnRequest req) {
    return new TransactionRecord(
        UUID.fromString(req.txnId()), T0, T0, req, Status.RECEIVED, null, "gw-1", null, null, null,
        req.encryptedPin(), null, null, null);
  }

  private TransactionRecord inserted() {
    String id = TestData.txnId();
    TransactionRecord rec = received(TestData.p2p(id, "key-" + id, 5_000));
    repo.insert(rec);
    return rec;
  }

  /** Runs a locking read in a transaction of its own. */
  private Optional<TransactionRecord> locked(java.util.function.Supplier<Optional<TransactionRecord>> read) {
    Optional<TransactionRecord> found = tx.execute(s -> read.get());
    return found == null ? Optional.empty() : found;
  }

  @Test
  void anInsertedTransactionReadsBackAsAccepted() {
    TransactionRecord rec = inserted();

    TransactionRecord found = repo.find(rec.txnId()).orElseThrow();

    assertThat(found.status()).isEqualTo(Status.RECEIVED);
    assertThat(found.replyTo()).isEqualTo("gw-1");
    assertThat(found.request().txnId()).isEqualTo(rec.request().txnId());
    assertThat(found.request().amount().valueMinor()).isEqualTo(5_000);
    assertThat(found.encryptedPin()).isEqualTo(rec.encryptedPin());
    assertThat(repo.find(UUID.randomUUID())).isEmpty();
  }

  @Test
  void anUpdateKeepsEveryMutableColumnAndATerminalStateDropsThePin() {
    TransactionRecord rec = inserted();
    ErrorInfo error = new ErrorInfo("MW-CORE-INSUFFICIENT_BALANCE", "Insufficient balance", "অপর্যাপ্ত ব্যালেন্স", false);
    TransactionRecord moved =
        rec.moved(Status.HOLDING, T0.plusSeconds(1), null).withCore("CORE-1", 500L).withHold("HOLD-1").withPartnerRef("REF-1");
    repo.update(moved);
    repo.update(moved.moved(Status.FAILED, T0.plusSeconds(2), error));

    TransactionRecord found = repo.find(rec.txnId()).orElseThrow();
    assertThat(found.status()).isEqualTo(Status.FAILED);
    assertThat(found.previousStatus()).isEqualTo(Status.HOLDING);
    assertThat(found.feeMinor()).isEqualTo(500L);
    assertThat(found.coreRef()).isEqualTo("CORE-1");
    assertThat(found.holdId()).isEqualTo("HOLD-1");
    assertThat(found.partnerRef()).isEqualTo("REF-1");
    assertThat(found.error()).isEqualTo(error);
    assertThat(found.encryptedPin()).isNull();
    assertThat(found.updatedAt()).isEqualTo(T0.plusSeconds(2));
  }

  @Test
  void findForUpdateHoldsTheRowLockUntilTheTransactionEnds() throws Exception {
    TransactionRecord rec = inserted();
    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CompletableFuture<Void> holder =
        CompletableFuture.runAsync(
            () ->
                tx.executeWithoutResult(
                    s -> {
                      repo.findForUpdate(rec.txnId()).orElseThrow();
                      locked.countDown();
                      try {
                        release.await(10, TimeUnit.SECONDS);
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                      }
                    }));
    assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

    // a second locker gives up after lock_timeout while the first holds the row
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    s -> {
                      jdbc.execute("set local lock_timeout = '200ms'");
                      repo.findForUpdate(rec.txnId());
                    }))
        .isInstanceOf(DataAccessException.class);
    // a plain read is not blocked
    assertThat(repo.find(rec.txnId())).isPresent();

    release.countDown();
    holder.get(10, TimeUnit.SECONDS);
    assertThat(locked(() -> repo.findForUpdate(rec.txnId()))).isPresent();
  }

  @Test
  void aTransactionIsFoundByItsReversalAndAReversalTxnIdIsUnique() {
    TransactionRecord first = inserted();
    TransactionRecord second = inserted();
    UUID reversal = UUID.randomUUID();

    repo.update(first.withReversal(reversal));
    assertThat(locked(() -> repo.findForUpdateByReversal(reversal)))
        .map(TransactionRecord::txnId)
        .contains(first.txnId());
    assertThat(locked(() -> repo.findForUpdateByReversal(UUID.randomUUID()))).isEmpty();

    assertThatThrownBy(() -> repo.update(second.withReversal(reversal))).isInstanceOf(DuplicateKeyException.class);
  }

  @Test
  void enteredAtIsTheLatestEntryIntoAState() {
    TransactionRecord rec = inserted();
    repo.recordStep(rec.txnId(), Status.RECEIVED, Status.VALIDATING, "ValidationStarted", T0);
    repo.recordStep(rec.txnId(), Status.VALIDATING, Status.UNKNOWN, "DeadlineExpired", T0.plusSeconds(5));
    repo.recordStep(rec.txnId(), Status.UNKNOWN, Status.UNKNOWN, "x", T0.plusSeconds(9));

    assertThat(repo.enteredAt(rec.txnId(), Status.VALIDATING)).contains(T0);
    assertThat(repo.enteredAt(rec.txnId(), Status.UNKNOWN)).contains(T0.plusSeconds(9));
    assertThat(repo.enteredAt(rec.txnId(), Status.COMPLETED)).isEmpty();
  }

  @Test
  void backlogQueriesCountAndAgeAState() {
    TransactionRecord a = inserted();
    TransactionRecord b = inserted();
    inserted();
    repo.update(a.moved(Status.MANUAL_REVIEW, T0.plus(Duration.ofHours(1)), null));
    repo.update(b.moved(Status.MANUAL_REVIEW, T0.plus(Duration.ofHours(3)), null));

    assertThat(repo.countByStatus(Status.MANUAL_REVIEW)).isEqualTo(2);
    assertThat(repo.countByStatus(Status.RECEIVED)).isEqualTo(1);
    assertThat(repo.oldestUpdateInStatus(Status.MANUAL_REVIEW)).contains(T0.plus(Duration.ofHours(1)));
    assertThat(repo.oldestUpdateInStatus(Status.UNKNOWN)).isEmpty();
  }
}
