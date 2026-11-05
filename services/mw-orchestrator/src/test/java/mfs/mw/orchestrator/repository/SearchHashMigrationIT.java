package mfs.mw.orchestrator.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
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
 * V10 (A2/A3): rows written before the typed search hash carry {@code HMAC(id)} and no payee hash.
 * The migration re-hashes every row from its raw {@code initiator_id} and stored request, so an
 * old row is found exactly like a new one and an org id never matches an MSISDN.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {"mw.orchestrator.consumers.enabled=false", "mw.orchestrator.timer.enabled=false"})
@Import(TestInfrastructure.class)
class SearchHashMigrationIT {

  private static final String KEY = "test-only-search-hash-key-not-a-secret-0001";

  private final TransactionService transactions;
  private final SearchHashMigration migration;
  private final SearchHash hash;
  private final DataSource dataSource;
  private final JdbcTemplate jdbc;

  @Autowired
  SearchHashMigrationIT(
      TransactionService transactions,
      SearchHashMigration migration,
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

  /** Makes a row look as V8 wrote it: untyped hash of the bare id, no type, no payee hash. */
  private void asWrittenBeforeV9(String txnId, String initiatorId) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    byte[] untyped = mac.doFinal(initiatorId.getBytes(StandardCharsets.UTF_8));
    jdbc.update(
        "update transaction set initiator_hash = ?, initiator_type = null, payee_hash = null where txn_id = ?::uuid",
        untyped, txnId);
  }

  private byte[] column(String col, String txnId) {
    return jdbc.queryForObject("select " + col + " from transaction where txn_id = ?::uuid", byte[].class, txnId);
  }

  @Test
  void oldRowsAreRehashedWithTheirTypeAndGetTheirPayeeHash() throws Exception {
    TxnRequest p2p = TestData.p2p(TestData.txnId(), "k1-" + TestData.txnId(), 5_000);
    TxnRequest bill = TestData.billPay(TestData.txnId(), "k2-" + TestData.txnId(), 66_700, "1234567");
    TxnRequest child = TestData.bulkChild(TestData.txnId(), "k3-" + TestData.txnId(), 1_000, "01711000003");
    transactions.accept(p2p, null);
    transactions.accept(bill, null);
    transactions.accept(child, null);
    asWrittenBeforeV9(p2p.txnId(), "01711000001");
    asWrittenBeforeV9(bill.txnId(), "01711000001");
    asWrittenBeforeV9(child.txnId(), "ORG001");

    try (Connection c = dataSource.getConnection()) {
      assertThat(migration.rehash(c)).isEqualTo(3);
    }

    assertThat(column("initiator_hash", p2p.txnId())).isEqualTo(hash.of("MSISDN", "01711000001"));
    assertThat(column("initiator_hash", child.txnId())).isEqualTo(hash.of("ORG", "ORG001"));
    assertThat(column("payee_hash", bill.txnId())).isEqualTo(hash.of("BILLER", "DESCO"));
    assertThat(column("payee_hash", p2p.txnId())).isNull();
    assertThat(jdbc.queryForObject("select initiator_type from transaction where txn_id = ?::uuid", String.class, child.txnId()))
        .isEqualTo("ORG");
  }

  @Test
  void theMigrationIsRegisteredWithFlywayAsVersion10() {
    assertThat(migration.getVersion().getVersion()).isEqualTo("10");
    assertThat(jdbc.queryForObject(
            "select count(*) from flyway_schema_history where version = '10' and success", Integer.class))
        .isEqualTo(1);
  }
}
