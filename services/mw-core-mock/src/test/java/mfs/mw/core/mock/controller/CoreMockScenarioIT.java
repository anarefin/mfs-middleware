package mfs.mw.core.mock.controller;

import static org.assertj.core.api.Assertions.assertThat;

import mfs.mw.core.mock.TestInfrastructure;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
 * The core party on the unified mock-control API (ADR D33, FRS MCK-04 core part, MCT-01..03, 7.3 "same
 * scenario controls"; ticket 103): scenarios scoped by operation, key and next-N shape /core/v1 answers,
 * the switch closes it, the journal lists the commands.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestInfrastructure.class)
class CoreMockScenarioIT {

  private static final String A = "01711000003";
  private static final String B = "01711000004";
  private static final String C = "01711000005";
  private static final long SEED = 1_000_000;

  private final CoreRest rest;
  private final JdbcTemplate jdbc;

  @Autowired
  CoreMockScenarioIT(MockMvcTester mvc, JsonMapper json, JdbcTemplate jdbc) {
    this.rest = new CoreRest(mvc, json);
    this.jdbc = jdbc;
  }

  @BeforeEach
  @AfterEach
  void reset() {
    assertThat(rest.control("POST", "/mock-control/reset", null)).hasStatusOk();
    jdbc.update("delete from outbox");
  }

  private void scenario(String body) {
    assertThat(rest.control("POST", "/mock-control/core/scenarios", body)).hasStatus(201);
  }

  @Test
  void aTimeoutOnHoldForTheNextCallHoldsTheAnswerThenTheCoreAnswersAtOnce() {
    scenario("{\"operation\":\"hold\",\"behaviour\":\"TIMEOUT\",\"delayMs\":400,\"nextN\":1}");

    long t0 = System.nanoTime();
    MvcTestResult first = rest.core("/core/v1/holds", CoreRest.signed("TOPUP", A, "MSISDN", A, 5000));
    long firstMs = (System.nanoTime() - t0) / 1_000_000;
    assertThat(first).hasStatusOk();
    assertThat(firstMs).as("the answer was held").isGreaterThanOrEqualTo(400);
    assertThat(rest.available(A)).as("a timeout still applies the hold (amount + fee 200)").isEqualTo(SEED - 5200);
    assertThat(rest.body(rest.control("GET", "/mock-control/core/scenarios", null))).as("next-N used up").isEmpty();

    long t1 = System.nanoTime();
    MvcTestResult second = rest.core("/core/v1/holds", CoreRest.signed("TOPUP", A, "MSISDN", A, 5000));
    assertThat(second).hasStatusOk();
    assertThat((System.nanoTime() - t1) / 1_000_000).isLessThan(400);
    assertThat(rest.body(second).path("status").asString()).isEqualTo("HELD");
  }

  @Test
  void aBusinessErrorForOneMsisdnRefusesOnlyItsTransfersAndARepeatIsTheSameRefusal() {
    scenario("{\"operation\":\"transfer\",\"behaviour\":\"BUSINESS_ERROR\",\"code\":\"INSUFFICIENT_BALANCE\",\"key\":\"" + A + "\"}");

    CoreRest.Signed refused = CoreRest.signed("P2P", A, "WALLET", C, 10000);
    MvcTestResult r = rest.core("/core/v1/transfers", refused);
    assertThat(r).hasStatus(422);
    assertThat(rest.body(r).path("code").asString()).isEqualTo("MW-CORE-INSUFFICIENT_BALANCE");
    assertThat(rest.available(A)).as("nothing applied").isEqualTo(SEED);
    assertThat(rest.body(rest.core("/core/v1/transfers", refused)).path("code").asString()).isEqualTo("MW-CORE-INSUFFICIENT_BALANCE");

    MvcTestResult other = rest.core("/core/v1/transfers", CoreRest.signed("P2P", B, "WALLET", C, 10000));
    assertThat(other).hasStatusOk();
    assertThat(rest.body(other).path("status").asString()).isEqualTo("POSTED");
    assertThat(rest.available(B)).isEqualTo(SEED - 10500);
  }

  @Test
  void aBusinessErrorOnARead() {
    scenario("{\"operation\":\"limits\",\"behaviour\":\"BUSINESS_ERROR\",\"code\":\"LIMIT_EXCEEDED_DAILY\"}");

    MvcTestResult r = rest.corePost("/core/v1/limits/check",
        "{\"walletId\":\"" + A + "\",\"txnType\":\"P2P\",\"amount\":{\"valueMinor\":1000,\"currency\":\"BDT\"}}");

    assertThat(r).hasStatus(422);
    assertThat(rest.body(r).path("code").asString()).isEqualTo("MW-CORE-LIMIT_EXCEEDED_DAILY");
  }

  @Test
  void anHttp5xxAfterTheTransferIsAppliedAndItsResultEventIsLost() {
    scenario("{\"operation\":\"transfer\",\"behaviour\":\"HTTP_5XX\",\"httpStatus\":502}");

    MvcTestResult r = rest.core("/core/v1/transfers", CoreRest.signed("P2P", A, "WALLET", C, 10000));

    assertThat(r).hasStatus(502);
    assertThat(rest.body(r).path("code").asString()).isEqualTo("MW-SYS-CORE_ERROR");
    assertThat(rest.available(A)).as("applied: an unknown outcome for the caller").isEqualTo(SEED - 10500);
    assertThat(jdbc.queryForObject("select count(*) from outbox", Long.class)).isZero();
  }

  @Test
  void maintenanceAnswers503AndAppliesNothing() {
    assertThat(rest.control("PUT", "/mock-control/core/switch", "{\"state\":\"MAINTENANCE\"}")).hasStatusOk();

    MvcTestResult r = rest.core("/core/v1/holds", CoreRest.signed("TOPUP", A, "MSISDN", A, 5000));
    assertThat(r).hasStatus(503);
    assertThat(rest.body(r).path("code").asString()).isEqualTo("MW-SYS-CORE_UNAVAILABLE");

    assertThat(rest.control("PUT", "/mock-control/core/switch", "{\"state\":\"UP\"}")).hasStatusOk();
    assertThat(rest.available(A)).isEqualTo(SEED);
  }

  @Test
  void theJournalListsTheCommandsWithTheirKeys() {
    rest.core("/core/v1/holds", CoreRest.signed("TOPUP", A, "MSISDN", A, 5000));
    rest.core("/core/v1/transfers", CoreRest.signed("P2P", B, "WALLET", C, 1000));

    JsonNode holds = rest.body(rest.control("GET", "/mock-control/core/journal?path=hold", null));
    assertThat(holds).hasSize(1);
    assertThat(holds.get(0).path("path").asString()).isEqualTo("/hold/HoldFunds");
    assertThat(holds.get(0).path("body").asString()).contains("debit=" + A);
    assertThat(rest.body(rest.control("GET", "/mock-control/core/journal?contains=" + C, null))).hasSize(1);
  }

  @Test
  void anUnknownPartyOrOperationIsRefused() {
    assertThat(rest.control("GET", "/mock-control/ocs/scenarios", null)).hasStatus(404);
    assertThat(rest.control("POST", "/mock-control/core/scenarios", "{\"operation\":\"topup\",\"behaviour\":\"TIMEOUT\"}"))
        .hasStatus(400);
    JsonNode parties = rest.body(rest.control("GET", "/mock-control/parties", null));
    assertThat(parties.get(0).path("name").asString()).isEqualTo("core");
  }
}
