package mfs.mw.orchestrator.service.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import mfs.mw.orchestrator.TestData;
import mfs.mw.orchestrator.TestInfrastructure;
import mfs.mw.orchestrator.dto.ConfigChangedEvent;
import mfs.mw.orchestrator.dto.TxnRequest;
import mfs.mw.orchestrator.service.TransactionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * An approved routing change is used without a restart (ticket 98, FRS PLT-041). The safety reload
 * is the same {@link RoutingCatalog#reload()} the 30 second job calls; this test calls it, so it
 * does not wait.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {"mw.orchestrator.consumers.enabled=false", "mw.orchestrator.timer.enabled=false"})
@Import(TestInfrastructure.class)
class RoutingRuleReloadIT {

  private final TransactionService transactions;
  private final RoutingCatalog catalog;
  private final JdbcTemplate jdbc;

  @Autowired
  RoutingRuleReloadIT(TransactionService transactions, RoutingCatalog catalog, JdbcTemplate jdbc) {
    this.transactions = transactions;
    this.catalog = catalog;
    this.jdbc = jdbc;
  }

  @BeforeEach
  void clean() {
    for (String t : List.of("outbox", "inbox", "saga_timer", "saga_step", "idempotency", "transaction")) {
      jdbc.update("delete from " + t);
    }
  }

  @AfterEach
  void restore() {
    jdbc.update("delete from routing_rule where id = 'topup-gp-app'");
    catalog.noteHealth("ocs-app", null);
    catalog.reload();
  }

  @Test
  void anApprovedRuleIsUsedOnReloadWithoutARestart() {
    catalog.apply(
        new ConfigChangedEvent(
            1,
            "routing-rule/topup-gp-app",
            ConfigChangedEvent.ConfigType.ROUTING_RULE,
            ConfigChangedEvent.Change.UPSERT,
            1,
            Map.of(
                "txnType", "TOPUP",
                "operatorCode", "gp",
                "channel", "CUSTOMER_APP",
                "primaryPartnerId", "ocs-app",
                "enabled", true),
            Instant.parse("2026-11-20T08:00:00Z"),
            "maker",
            "checker",
            "corr-rule"));
    catalog.noteHealth("ocs-app", "DOWN");

    TxnRequest req = TestData.topup(TestData.txnId(), "key-reload-" + UUID.randomUUID(), 2_600, "01711000002");
    transactions.accept(req, null);

    String code =
        jdbc.queryForObject(
            "select error_code from transaction where txn_id = ?::uuid", String.class, req.txnId());
    assertThat(code).isEqualTo("MW-SYS-PARTNER_CIRCUIT_OPEN");
  }
}
