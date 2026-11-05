package mfs.mw.core.mock.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import mfs.mw.core.mock.TestInfrastructure;
import mfs.mw.core.mock.entity.Hold;
import mfs.mw.core.mock.entity.LedgerEntry;
import mfs.mw.core.mock.entity.Wallet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The core's ledger repositories against PostgreSQL (Testcontainers, the module's JVM-scoped
 * {@link TestInfrastructure}): wallets (lookup, row lock, balance checks, PIN lockout, daily
 * debits), holds, ledger entries and reversals, and the command log (dedup key, advisory lock,
 * latest event). Each test uses wallets and txnIds of its own next to the seeded ones.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestInfrastructure.class)
class CoreRepositoriesIT {

  private final WalletRepository wallets;
  private final HoldRepository holds;
  private final LedgerRepository ledger;
  private final CommandLogRepository commands;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;

  private String walletId;
  private String msisdn;

  @Autowired
  CoreRepositoriesIT(
      WalletRepository wallets,
      HoldRepository holds,
      LedgerRepository ledger,
      CommandLogRepository commands,
      JdbcTemplate jdbc,
      TransactionTemplate tx) {
    this.wallets = wallets;
    this.holds = holds;
    this.ledger = ledger;
    this.commands = commands;
    this.jdbc = jdbc;
    this.tx = tx;
  }

  @BeforeEach
  void newWallet() {
    long n = System.nanoTime() % 100_000_000L;
    msisdn = String.format("0151%07d", n % 10_000_000L); // 015 is never seeded
    walletId = "TEST-" + UUID.randomUUID();
    jdbc.update(
        "insert into wallet (id, msisdn, type, balance_minor, pin_hash) values (?, ?, 'CUSTOMER', 10000, 'not-a-real-hash')",
        walletId, msisdn);
  }

  private static String txnId() {
    return UUID.randomUUID().toString();
  }

  // --- wallets -------------------------------------------------------------------------------

  @Test
  void aWalletIsResolvedByIdOrMsisdn() {
    assertThat(wallets.resolveId(walletId)).contains(walletId);
    assertThat(wallets.resolveId(msisdn)).contains(walletId);
    assertThat(wallets.resolveId("01999999999-none")).isEmpty();
  }

  @Test
  void balanceAndHeldMoveByDeltaAndCannotGoNegative() {
    wallets.adjustBalance(walletId, -2_500);
    wallets.adjustHeld(walletId, 1_000);

    Wallet w = wallets.get(walletId);
    assertThat(w.balanceMinor()).isEqualTo(7_500);
    assertThat(w.heldMinor()).isEqualTo(1_000);
    assertThatThrownBy(() -> wallets.adjustBalance(walletId, -10_000)).isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> wallets.adjustHeld(walletId, -2_000)).isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void lockHoldsTheWalletRowUntilTheTransactionEnds() throws Exception {
    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CompletableFuture<Void> holder =
        CompletableFuture.runAsync(
            () ->
                tx.executeWithoutResult(
                    s -> {
                      wallets.lock(walletId);
                      locked.countDown();
                      try {
                        release.await(10, TimeUnit.SECONDS);
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                      }
                    }));
    assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    s -> {
                      jdbc.execute("set local lock_timeout = '200ms'");
                      wallets.lock(walletId);
                    }))
        .isInstanceOf(DataAccessException.class);

    release.countDown();
    holder.get(10, TimeUnit.SECONDS);
  }

  @Test
  void wrongPinsLockTheWalletAtTheLimitAndAResetClearsTheCount() {
    assertThat(wallets.recordPinFailure(walletId, 3)).isFalse();
    wallets.resetPinFailures(walletId);
    assertThat(wallets.recordPinFailure(walletId, 3)).isFalse();
    assertThat(wallets.recordPinFailure(walletId, 3)).isFalse();
    assertThat(wallets.recordPinFailure(walletId, 3)).isTrue();
    assertThat(wallets.get(walletId).status()).isEqualTo("LOCKED");
  }

  @Test
  void debitedSinceSumsTransferDebitsOnly() {
    Instant t = Instant.parse("2026-11-03T00:00:00Z");
    String txn = txnId();
    ledger.append(txn + ":post", txn, walletId, "DEBIT", "TRANSFER", 1_000, t.plusSeconds(10));
    ledger.append(txn + ":post", txn, walletId, "DEBIT", "FEE", 50, t.plusSeconds(10));
    ledger.append(txn + ":post", txn, walletId, "CREDIT", "TRANSFER", 700, t.plusSeconds(10));
    ledger.append(txn + ":post", txn, walletId, "DEBIT", "TRANSFER", 300, t.minusSeconds(10));

    assertThat(wallets.debitedSince(walletId, t)).isEqualTo(1_000);
  }

  // --- holds ---------------------------------------------------------------------------------

  @Test
  void aHoldIsStoredLockedAndMovedAndTheLatestOfATxnIsFound() {
    String txn = txnId();
    holds.insert("HOLD-A-" + txn, walletId, txn, 2_000, 100);
    holds.insert("HOLD-B-" + txn, walletId, txn, 3_000, 0);

    Optional<Hold> a = tx.execute(s -> holds.lock("HOLD-A-" + txn));
    assertThat(a).hasValueSatisfying(h -> {
      assertThat(h.amountMinor()).isEqualTo(2_000);
      assertThat(h.feeMinor()).isEqualTo(100);
      assertThat(h.status()).isEqualTo("HELD");
    });
    holds.setStatus("HOLD-A-" + txn, "COMMITTED");
    Optional<Hold> committed = tx.execute(s -> holds.lock("HOLD-A-" + txn));
    assertThat(committed).hasValueSatisfying(h -> assertThat(h.status()).isEqualTo("COMMITTED"));
    assertThat(holds.latestForTxn(txn)).map(Hold::id).contains("HOLD-B-" + txn);
    assertThat(holds.latestForTxn(txnId())).isEmpty();
    assertThatThrownBy(() -> holds.setStatus("HOLD-B-" + txn, "SPENT")).isInstanceOf(DataIntegrityViolationException.class);
  }

  // --- ledger and reversals ------------------------------------------------------------------

  @Test
  void entriesOfATxnComeBackInOrderAndAReversalIsRecordedOnce() {
    String txn = txnId();
    Instant at = Instant.parse("2026-11-03T08:00:00Z");
    ledger.append(txn + ":post", txn, walletId, "DEBIT", "TRANSFER", 1_000, at);
    ledger.append(txn + ":post", txn, walletId, "CREDIT", "TRANSFER", 1_000, at);

    assertThat(ledger.entriesOf(txn)).extracting(LedgerEntry::direction).containsExactly("DEBIT", "CREDIT");
    assertThat(ledger.isReversed(txn)).isFalse();

    ledger.markReversed(txn, txnId());
    assertThat(ledger.isReversed(txn)).isTrue();
    assertThatThrownBy(() -> ledger.markReversed(txn, txnId())).isInstanceOf(DuplicateKeyException.class);
    assertThat(ledger.nextCoreSequence()).isLessThan(ledger.nextCoreSequence());
  }

  // --- command log ---------------------------------------------------------------------------

  @Test
  void aCommandIsLoggedOnceAndTheLatestNonQueryEventOfATxnIsFound() {
    String txn = txnId();
    commands.save(txn + ":validate", "ValidateTransaction", txn, "{\"eventType\":\"TransactionValidated\"}");
    commands.save(txn + ":post", "PostTransfer", txn, "{\"eventType\":\"TransferPosted\"}");
    commands.save(txn + ":query:1", "QueryTransaction", txn, "{\"eventType\":\"TransactionStatus\"}");

    assertThat(commands.findEvent(txn + ":validate")).hasValueSatisfying(e -> assertThat(e).contains("TransactionValidated"));
    assertThat(commands.findEvent(txn + ":nothing")).isEmpty();
    assertThat(commands.latestEventOfTxn(txn)).hasValueSatisfying(e -> assertThat(e).contains("TransferPosted"));
    assertThatThrownBy(() -> commands.save(txn + ":post", "PostTransfer", txn, "{}")).isInstanceOf(DuplicateKeyException.class);
  }

  @Test
  void theCommandLockSerialisesTheSameCommandIdUntilTheTransactionEnds() throws Exception {
    String commandId = txnId() + ":post";
    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CompletableFuture<Void> holder =
        CompletableFuture.runAsync(
            () ->
                tx.executeWithoutResult(
                    s -> {
                      commands.lock(commandId);
                      locked.countDown();
                      try {
                        release.await(10, TimeUnit.SECONDS);
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                      }
                    }));
    assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    s -> {
                      jdbc.execute("set local lock_timeout = '200ms'");
                      commands.lock(commandId);
                    }))
        .isInstanceOf(DataAccessException.class);
    tx.executeWithoutResult(s -> commands.lock(commandId + "-other")); // another command id is free

    release.countDown();
    holder.get(10, TimeUnit.SECONDS);
  }
}
