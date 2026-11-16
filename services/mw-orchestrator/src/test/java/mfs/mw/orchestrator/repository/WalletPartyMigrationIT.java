package mfs.mw.orchestrator.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.util.List;
import javax.sql.DataSource;
import mfs.mw.orchestrator.TestData;
import mfs.mw.orchestrator.TestInfrastructure;
import mfs.mw.orchestrator.dto.TxnRequest;
import mfs.mw.orchestrator.service.TransactionService;
import mfs.mw.orchestrator.util.SearchHash;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V13 (ADR D25): transactions written before the wallet party index have no {@code wallet_txn}
 * rows. The migration writes them from the stored request, keyed like new ones, and leaves rows
 * that are already there alone.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {"mw.orchestrator.consumers.enabled=false", "mw.orchestrator.timer.enabled=false"})
@Import(TestInfrastructure.class)
class WalletPartyMigrationIT {

  private final TransactionService transactions;
  private final WalletPartyMigration migration;
  private final SearchHash hash;
  private final DataSource dataSource;
  private final JdbcTemplate jdbc;

  @Autowired
  WalletPartyMigrationIT(
      TransactionService transactions,
      WalletPartyMigration migration,
      SearchHash hash,
      DataSource dataSource,
      JdbcTemplate jdbc) {
    this.transactions = transactions;
    this.migration = migration;
    this.hash = hash;
    this.dataSource = dataSource;
    this.jdbc = jdbc;
  }

  @BeforeEach
  void clean() {
    for (String t :
        List.of("outbox", "inbox", "saga_timer", "saga_step", "partner_call", "core_call", "idempotency",
            "transaction")) {
      jdbc.update("delete from " + t);
    }
  }

  private List<String> rows(String txnId) {
    return jdbc.query(
        "select wallet_hash, direction from wallet_txn where txn_id = ?::uuid order by direction desc",
        (rs, i) -> rs.getString("direction") + (java.util.Arrays.equals(rs.getBytes("wallet_hash"),
            hash.of("WALLET", "01711000001")) ? " own" : " other"),
        txnId);
  }

  @Test
  void oldTransactionsGetTheirWalletRowsOnceOnly() throws Exception {
    TxnRequest p2p = TestData.p2p(TestData.txnId(), "k1-" + TestData.txnId(), 5_000);
    TxnRequest topup = TestData.topup(TestData.txnId(), "k2-" + TestData.txnId(), 2_000, "01711000002");
    TxnRequest child = TestData.bulkChild(TestData.txnId(), "k3-" + TestData.txnId(), 1_000, "01711000001");
    transactions.accept(p2p, null);
    transactions.accept(topup, null);
    transactions.accept(child, null);
    // as written before V12: no wallet rows (keep one, to show it is left alone)
    jdbc.update("delete from wallet_txn where not (txn_id = ?::uuid and direction = 'OUT')", p2p.txnId());

    try (Connection c = dataSource.getConnection()) {
      assertThat(migration.backfill(c)).isEqualTo(3);
      assertThat(migration.backfill(c)).isZero();
    }

    assertThat(rows(p2p.txnId())).containsExactly("OUT own", "IN other");
    assertThat(rows(topup.txnId())).containsExactly("OUT own");
    assertThat(rows(child.txnId())).containsExactly("IN own");
  }

  @Test
  void theMigrationIsRegisteredWithFlywayAsVersion13() {
    assertThat(migration.getVersion().getVersion()).isEqualTo("13");
    assertThat(jdbc.queryForObject(
            "select count(*) from flyway_schema_history where version = '13' and success", Integer.class))
        .isEqualTo(1);
    assertThat(jdbc.queryForObject(
            "select count(*) from flyway_schema_history where version = '12' and success", Integer.class))
        .isEqualTo(1);
  }
}
