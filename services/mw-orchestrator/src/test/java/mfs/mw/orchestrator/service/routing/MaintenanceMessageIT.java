package mfs.mw.orchestrator.service.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import mfs.mw.orchestrator.TestData;
import mfs.mw.orchestrator.TestInfrastructure;
import mfs.mw.orchestrator.dto.TxnRequest;
import mfs.mw.orchestrator.service.TransactionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** A disabled biller is refused with the Bangla and English maintenance text (ticket 98, FRS PLT-043). */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {"mw.orchestrator.consumers.enabled=false", "mw.orchestrator.timer.enabled=false"})
@Import(TestInfrastructure.class)
class MaintenanceMessageIT {

  private final TransactionService transactions;
  private final RoutingCatalog catalog;
  private final JdbcTemplate jdbc;
  private final JsonMapper json;

  @Autowired
  MaintenanceMessageIT(TransactionService transactions, RoutingCatalog catalog, JdbcTemplate jdbc, JsonMapper json) {
    this.transactions = transactions;
    this.catalog = catalog;
    this.jdbc = jdbc;
    this.json = json;
  }

  @BeforeEach
  void clean() {
    for (String t : List.of("outbox", "inbox", "saga_timer", "saga_step", "idempotency", "transaction")) {
      jdbc.update("delete from " + t);
    }
  }

  @AfterEach
  void restore() {
    jdbc.update("delete from maintenance_message where id = 'desco'");
    catalog.reload();
  }

  @Test
  void aDisabledBillerReturnsBanglaAndEnglish() {
    jdbc.update(
        "insert into maintenance_message (id, biller_code, message_en, message_bn, enabled)"
            + " values ('desco', 'DESCO', 'DESCO is under maintenance', 'ডেসকো রক্ষণাবেক্ষণে আছে', true)");
    catalog.reload();

    TxnRequest req = TestData.billPay(TestData.txnId(), "key-maint-" + UUID.randomUUID(), 3_100, "BILL-1");
    transactions.accept(req, null);

    List<JsonNode> statuses =
        jdbc.queryForList(
                "select payload::text from outbox where topic = 'mw.txn.status.v1' and msg_key = ? order by id",
                String.class,
                req.txnId())
            .stream()
            .map(json::readTree)
            .toList();
    JsonNode error = statuses.get(statuses.size() - 1).path("error");
    assertThat(statuses).extracting(n -> n.path("status").asString()).containsExactly("RECEIVED", "REJECTED");
    assertThat(error.path("code").asString()).isEqualTo("MW-SYS-PARTNER_DISABLED");
    assertThat(error.path("message").asString()).isEqualTo("DESCO is under maintenance");
    assertThat(error.path("messageBn").asString()).isEqualTo("ডেসকো রক্ষণাবেক্ষণে আছে");
  }
}
