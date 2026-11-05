package mfs.mw.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import mfs.mw.orchestrator.MutableClock;
import mfs.mw.orchestrator.TestData;
import mfs.mw.orchestrator.TestInfrastructure;
import mfs.mw.orchestrator.contracts.ContractSchemas;
import mfs.mw.orchestrator.dto.TxnAction;
import mfs.mw.orchestrator.dto.TxnRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Operator actions approved by maker-checker in mw-admin-api (ticket 37, {@code mw.txn.action.v1}):
 * FORCE_COMMIT / FORCE_RELEASE leave MANUAL_REVIEW with a fresh commandId (the core re-decides
 * instead of replaying an earlier refusal), INQUIRE sends one status inquiry and keeps the state;
 * an action the state or flow does not allow changes nothing. Every action is audited with the
 * checker as actor, and an actionId is applied once.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {"mw.orchestrator.consumers.enabled=false", "mw.orchestrator.timer.enabled=false"})
@Import({TestInfrastructure.class, SagaServiceIT.TestClock.class})
class OperatorActionIT {

  private final TransactionService intake;
  private final OperatorActionService actions;
  private final MutableClock clock;
  private final JdbcTemplate jdbc;
  private final JsonMapper json;

  @Autowired
  OperatorActionIT(
      TransactionService intake, OperatorActionService actions, Clock clock, JdbcTemplate jdbc, JsonMapper json) {
    this.intake = intake;
    this.actions = actions;
    this.clock = (MutableClock) clock;
    this.jdbc = jdbc;
    this.json = json;
  }

  @BeforeEach
  void clean() {
    clock.set(TestData.NOW);
    for (String t : List.of("outbox", "inbox", "saga_timer", "saga_step", "idempotency", "core_call", "partner_call", "transaction")) {
      jdbc.update("delete from " + t);
    }
  }

  private UUID parked(TxnRequest req, String status, String holdId) {
    intake.accept(req, null);
    UUID txnId = UUID.fromString(req.txnId());
    jdbc.update("update transaction set status = ?, hold_id = ? where txn_id = ?", status, holdId, txnId);
    jdbc.update("delete from saga_timer");
    jdbc.update("delete from outbox");
    return txnId;
  }

  private static TxnAction action(UUID txnId, String action) {
    return new TxnAction(
        1, UUID.randomUUID().toString(), txnId.toString(), action, "corr-op-1", UUID.randomUUID().toString(),
        "ops-maker01", "ops-checker01", "checked with the partner", TestData.NOW);
  }

  private String status(UUID txnId) {
    return jdbc.queryForObject("select status from transaction where txn_id = ?", String.class, txnId);
  }

  private List<JsonNode> outbox(String topic) {
    return jdbc.queryForList("select payload::text from outbox where topic = ? order by id", String.class, topic)
        .stream().map(json::readTree).toList();
  }

  private List<JsonNode> operatorAudits() {
    return outbox("mw.audit.event.v1").stream()
        .filter(e -> e.path("action").asString().equals("txn.operator.action"))
        .toList();
  }

  @Test
  void forceCommitLeavesManualReviewWithAFreshCommitCommandAndIsAuditedOnce() {
    String id = TestData.txnId();
    UUID txnId = parked(TestData.topup(id, "key-" + id, 5_000, "01711000001"), "MANUAL_REVIEW", "HOLD-1");
    TxnAction force = action(txnId, "FORCE_COMMIT");

    actions.handle(force);
    actions.handle(force); // redelivered: applied once

    assertThat(status(txnId)).isEqualTo("COMMITTING");
    List<JsonNode> commands = outbox("core.payment.command.v1");
    assertThat(commands).hasSize(1);
    assertThat(commands.get(0).path("commandType").asString()).isEqualTo("CommitHold");
    assertThat(commands.get(0).path("commandId").asString()).isEqualTo(id + ":commit:manual-1");

    List<JsonNode> audits = operatorAudits();
    assertThat(audits).hasSize(1);
    JsonNode a = audits.get(0);
    assertThat(a.path("actor").path("type").asString()).isEqualTo("user");
    assertThat(a.path("actor").path("id").asString()).isEqualTo("ops-checker01");
    assertThat(a.path("outcome").asString()).isEqualTo("SUCCESS");
    assertThat(a.path("before").path("status").asString()).isEqualTo("MANUAL_REVIEW");
    assertThat(a.path("after").path("status").asString()).isEqualTo("COMMITTING");
    assertThat(a.path("after").path("action").asString()).isEqualTo("FORCE_COMMIT");
    assertThat(a.path("after").path("makerId").asString()).isEqualTo("ops-maker01");
    assertThat(ContractSchemas.schema("events/mw-audit-dw/mw.audit.event.v1.schema.json").validate(a)).isEmpty();
  }

  @Test
  void aSecondForcedCommitGetsItsOwnCommandId() {
    String id = TestData.txnId();
    UUID txnId = parked(TestData.topup(id, "key-" + id, 5_000, "01711000001"), "MANUAL_REVIEW", "HOLD-1");
    actions.handle(action(txnId, "FORCE_COMMIT"));
    jdbc.update("update transaction set status = 'MANUAL_REVIEW' where txn_id = ?", txnId);
    jdbc.update("delete from outbox");

    actions.handle(action(txnId, "FORCE_COMMIT"));

    assertThat(outbox("core.payment.command.v1").get(0).path("commandId").asString()).isEqualTo(id + ":commit:manual-2");
  }

  @Test
  void forceReleaseSendsReleaseHold() {
    String id = TestData.txnId();
    UUID txnId = parked(TestData.topup(id, "key-" + id, 5_000, "01711000001"), "MANUAL_REVIEW", "HOLD-1");

    actions.handle(action(txnId, "FORCE_RELEASE"));

    assertThat(status(txnId)).isEqualTo("RELEASING");
    JsonNode c = outbox("core.payment.command.v1").get(0);
    assertThat(c.path("commandType").asString()).isEqualTo("ReleaseHold");
    assertThat(c.path("commandId").asString()).isEqualTo(id + ":release:manual-1");
  }

  @Test
  void anInquiryInManualReviewAsksThePartnerAndKeepsTheState() {
    String id = TestData.txnId();
    UUID txnId = parked(TestData.topup(id, "key-" + id, 5_000, "01711000001"), "MANUAL_REVIEW", "HOLD-1");

    actions.handle(action(txnId, "INQUIRE"));

    assertThat(status(txnId)).isEqualTo("MANUAL_REVIEW");
    List<JsonNode> requests = outbox("mw.partner.request.telco.v1");
    assertThat(requests).hasSize(1);
    assertThat(requests.get(0).path("payload").path("operation").asString()).isEqualTo("STATUS_QUERY");
    assertThat(operatorAudits()).singleElement()
        .satisfies(a -> assertThat(a.path("after").path("status").asString()).isEqualTo("MANUAL_REVIEW"));
  }

  @Test
  void anActionTheStateOrFlowDoesNotAllowChangesNothingAndIsAuditedAsRefused() {
    String p2p = TestData.txnId();
    UUID p2pTxn = parked(TestData.p2p(p2p, "key-" + p2p, 5_000), "MANUAL_REVIEW", null);
    String done = TestData.txnId();
    UUID completed = parked(TestData.topup(done, "key-" + done, 5_000, "01711000001"), "COMPLETED", "HOLD-2");

    actions.handle(action(p2pTxn, "FORCE_COMMIT")); // P2P has no commit step
    actions.handle(action(completed, "FORCE_RELEASE")); // not in MANUAL_REVIEW
    actions.handle(action(completed, "INQUIRE"));

    assertThat(status(p2pTxn)).isEqualTo("MANUAL_REVIEW");
    assertThat(status(completed)).isEqualTo("COMPLETED");
    assertThat(outbox("core.payment.command.v1")).isEmpty();
    assertThat(outbox("core.wallet.command.v1")).isEmpty();
    assertThat(outbox("mw.partner.request.telco.v1")).isEmpty();
    List<JsonNode> audits = operatorAudits();
    assertThat(audits).hasSize(3).allSatisfy(a -> {
      assertThat(a.path("outcome").asString()).isEqualTo("FAILURE");
      assertThat(a.path("after").path("refused").asString()).isEqualTo("MW-VAL-023");
    });
  }

  @Test
  void anActionForAnUnknownTransactionIsDropped() {
    actions.handle(action(UUID.fromString(TestData.txnId()), "FORCE_COMMIT"));
    assertThat(jdbc.queryForObject("select count(*) from outbox", Integer.class)).isZero();
  }
}
