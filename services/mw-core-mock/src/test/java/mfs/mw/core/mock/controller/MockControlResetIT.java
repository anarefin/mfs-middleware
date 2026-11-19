package mfs.mw.core.mock.controller;

import static org.assertj.core.api.Assertions.assertThat;

import mfs.mw.core.mock.TestInfrastructure;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The core's part of the global reset (FRS MCT-07, MCK-08, ticket 103): {@code POST /mock-control/reset}
 * brings the ledger back to the seed (also a wallet the seed API made, to its opening balance), clears the
 * scenarios, switch and journal, and a changed seed version loads that folder's files.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestInfrastructure.class)
class MockControlResetIT {

  private static final String A = "01711000006";
  private static final String B = "01711000007";
  private static final String PERSONA = "01712000099";
  private static final long SEED = 1_000_000;

  private final CoreRest rest;
  private final JdbcTemplate jdbc;

  @Autowired
  MockControlResetIT(MockMvcTester mvc, JsonMapper json, JdbcTemplate jdbc) {
    this.rest = new CoreRest(mvc, json);
    this.jdbc = jdbc;
  }

  @AfterEach
  void backToV1() {
    assertThat(rest.control("POST", "/mock-control/reset", "{\"seedVersion\":\"v1\"}")).hasStatusOk();
    jdbc.update("delete from wallet where id = ?", PERSONA);
  }

  private long total(String id) {
    return jdbc.queryForObject("select balance_minor from wallet where id = ?", Long.class, id);
  }

  @Test
  void theLedgerScenariosSwitchAndJournalGoBackToAFreshStart() {
    jdbc.update("insert into wallet (id, msisdn, type, balance_minor) values (?, ?, 'CUSTOMER', 777) on conflict do nothing",
        PERSONA, PERSONA);
    assertThat(rest.core("/core/v1/transfers", CoreRest.signed("P2P", A, "WALLET", B, 10000))).hasStatusOk();
    assertThat(rest.core("/core/v1/holds", CoreRest.signed("TOPUP", B, "MSISDN", B, 5000))).hasStatusOk();
    assertThat(rest.corePost("/core/v1/credits", "{\"txnId\":\"" + mfs.mw.core.mock.service.CoreCommandsTestSupport.txnId()
        + "\",\"txnType\":\"ADD_MONEY_BANK\",\"creditParty\":{\"type\":\"WALLET\",\"id\":\"" + PERSONA
        + "\"},\"amount\":{\"valueMinor\":1000,\"currency\":\"BDT\"}}")).hasStatusOk();
    assertThat(rest.control("POST", "/mock-control/core/scenarios", "{\"behaviour\":\"DELAY\",\"delayMs\":1}")).hasStatus(201);
    assertThat(rest.control("PUT", "/mock-control/core/switch", "{\"state\":\"SLOW\",\"latencyMinMs\":1}")).hasStatusOk();
    assertThat(rest.body(rest.control("GET", "/mock-control/core/journal", null))).isNotEmpty();

    MvcTestResult r = rest.control("POST", "/mock-control/reset", null);

    assertThat(r).hasStatusOk();
    JsonNode result = rest.body(r).path("parties").get(0);
    assertThat(result.path("party").asString()).isEqualTo("core");
    assertThat(result.path("status").asString()).isEqualTo("RESET");
    assertThat(result.path("seedVersion").asString()).isEqualTo("v1");
    assertThat(total(A)).isEqualTo(SEED);
    assertThat(rest.available(B)).as("holds gone").isEqualTo(SEED);
    assertThat(total(PERSONA)).as("a seed-API wallet keeps its opening balance").isEqualTo(777);
    assertThat(total("SETTLEMENT")).isEqualTo(1_000_000_000_000L);
    for (String table : new String[] {"entry", "hold", "command_log", "rest_request"}) {
      assertThat(jdbc.queryForObject("select count(*) from " + table, Long.class)).as(table).isZero();
    }
    assertThat(rest.body(rest.control("GET", "/mock-control/core/journal", null))).isEmpty();
    assertThat(rest.body(rest.control("GET", "/mock-control/core/scenarios", null))).isEmpty();
    assertThat(rest.body(rest.control("GET", "/mock-control/core/switch", null)).path("state").asString()).isEqualTo("UP");
  }

  @Test
  void aChangedSeedVersionReloadsThatFolderAndStaysInForce() {
    MvcTestResult r = rest.control("POST", "/mock-control/reset", "{\"seedVersion\":\"t1\"}");

    assertThat(r).hasStatusOk();
    assertThat(rest.body(r).path("parties").get(0).path("seedVersion").asString()).isEqualTo("t1");
    assertThat(total("01719000001")).isEqualTo(5000);
    assertThat(jdbc.queryForObject("select fee from tariff where txn_type = 'P2P'", Long.class)).isEqualTo(700);

    assertThat(rest.body(rest.control("POST", "/mock-control/reset", null)).path("parties").get(0).path("seedVersion")
        .asString()).as("the version in force").isEqualTo("t1");
  }

  @Test
  void anUnknownSeedVersionIsRefusedAndChangesNothing() {
    assertThat(rest.control("POST", "/mock-control/reset", "{\"seedVersion\":\"v404\"}")).hasStatus(400);
    assertThat(rest.control("POST", "/mock-control/reset", "{\"seedVersion\":\"../x\"}")).hasStatus(400);
  }
}
