package mfs.mw.core.mock.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import mfs.mw.core.mock.TestInfrastructure;
import mfs.mw.core.mock.dto.CoreCommand;
import mfs.mw.core.mock.dto.CoreEvent;
import mfs.mw.core.mock.dto.Fault.FaultMode;
import mfs.mw.core.mock.dto.FaultRequest;
import mfs.mw.core.mock.security.PinKeyProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Fault injection (ticket 14): every mode can be triggered, scoped by command type or txnId, and
 * is cleared by a reset. The ledger and the command log stay the truth, so QueryTransaction
 * reports the real outcome of a command whose reply was dropped.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestInfrastructure.class)
class FaultInjectionIT {

  static final String A = "01711000001";
  static final String B = "01711000002";

  private final CoreCommandService service;
  private final FaultRegistry faults;
  private final WalletSeeder seeder;
  private final JdbcTemplate jdbc;
  private final JsonMapper json;
  private final CoreCommandsTestSupport cmds;

  @Autowired
  FaultInjectionIT(
      CoreCommandService service,
      FaultRegistry faults,
      WalletSeeder seeder,
      JdbcTemplate jdbc,
      PinKeyProvider keys,
      JsonMapper json) {
    this.service = service;
    this.faults = faults;
    this.seeder = seeder;
    this.jdbc = jdbc;
    this.json = json;
    this.cmds = new CoreCommandsTestSupport(keys, json);
  }

  @BeforeEach
  void fresh() {
    faults.reset();
    seeder.reset();
    jdbc.update("delete from outbox");
  }

  @AfterEach
  void clear() {
    faults.reset();
  }

  private static String txn() {
    return CoreCommandsTestSupport.txnId();
  }

  private CoreEvent handle(CoreCommand c) {
    return cmds.valid(service.handle(c).orElseThrow(), c);
  }

  private long balance(String id) {
    return jdbc.queryForObject("select balance_minor from wallet where id = ?", Long.class, id);
  }

  private long outbox() {
    return jdbc.queryForObject("select count(*) from outbox", Long.class);
  }

  private static FaultRequest fault(FaultMode mode, String type, String txn, Long delay, String reason, Integer times) {
    return new FaultRequest(mode, type, txn, delay, reason, times);
  }

  @Test
  void aDroppedReplyIsNeverPublishedButQueryTransactionKnowsTheTrueOutcome() {
    faults.add(fault(FaultMode.DROP_REPLY, "PostTransfer", null, null, null, null));
    String txn = txn();
    CoreCommand post = cmds.post(txn, A, B, 100_000, "1234");

    CoreEvent decided = handle(post);

    assertThat(decided.eventType()).isEqualTo("TransferPosted");
    assertThat(outbox()).isZero();
    assertThat(balance(A)).isEqualTo(1_000_000 - 100_500); // the money did move

    CoreEvent byTxn = handle(cmds.query(txn));
    assertThat(json.valueToTree(byTxn.payload()).path("txnStatus").asString()).isEqualTo("POSTED");
    CoreEvent byCommand = handle(cmds.queryCommand(txn, post.commandId()));
    assertThat(json.valueToTree(byCommand.payload()).path("txnStatus").asString()).isEqualTo("POSTED");
    assertThat(outbox()).isEqualTo(2); // the query replies are not affected by a PostTransfer fault
  }

  @Test
  void aDroppedRejectionIsAlsoTrulyRecorded() {
    faults.add(fault(FaultMode.DROP_REPLY, null, null, null, null, null));
    String txn = txn();
    handle(cmds.post(txn, A, B, 100_000, "0000"));
    faults.reset();

    assertThat(outbox()).isZero();
    assertThat(json.valueToTree(handle(cmds.query(txn)).payload()).path("txnStatus").asString()).isEqualTo("REJECTED");
  }

  @Test
  void aDuplicatedReplyIsPublishedTwiceWithTheSameEventId() {
    faults.add(fault(FaultMode.DUPLICATE_REPLY, "PostTransfer", null, null, null, null));

    handle(cmds.post(txn(), A, B, 100_000, "1234"));

    List<String> ids = jdbc.queryForList("select payload->>'eventId' from outbox order by id", String.class);
    assertThat(ids).hasSize(2);
    assertThat(ids.get(0)).isEqualTo(ids.get(1));
    assertThat(balance(A)).isEqualTo(1_000_000 - 100_500); // one ledger effect
  }

  @Test
  void aDelayedReplyIsHeldBackUntilItsTimeHasCome() {
    faults.add(fault(FaultMode.DELAY, null, null, 60_000L, null, null));

    handle(cmds.post(txn(), A, B, 1_000, "1234"));

    assertThat(jdbc.queryForObject("select available_at > now() + interval '50 seconds' from outbox", Boolean.class)).isTrue();
  }

  @Test
  void anInjectedRejectionRefusesTheCommandWithoutEffectAndWithTheGivenReason() {
    faults.add(fault(FaultMode.REJECT, "PostTransfer", null, null, "CORE_UNAVAILABLE", null));
    String txn = txn();

    CoreEvent e = handle(cmds.post(txn, A, B, 100_000, "1234"));

    assertThat(e.eventType()).isEqualTo("TransferRejected");
    assertThat(e.outcome()).isEqualTo(CoreEvent.Outcome.FAILURE);
    assertThat(e.reasonCode()).isEqualTo("CORE_UNAVAILABLE");
    assertThat(balance(A)).isEqualTo(1_000_000);
    assertThat(jdbc.queryForObject("select count(*) from entry", Long.class)).isZero();
    assertThat(outbox()).isEqualTo(1);
  }

  @Test
  void rejectionUsesTheFailureEventOfEachCommandType() {
    faults.add(fault(FaultMode.REJECT, null, null, null, null, null));

    assertThat(handle(cmds.holdFunds(txn(), A, 1_000, "1234")).eventType()).isEqualTo("HoldRejected");
    assertThat(handle(cmds.commitHold(txn(), "H")).eventType()).isEqualTo("CommitFailed");
    assertThat(handle(cmds.releaseHold(txn(), "H")).eventType()).isEqualTo("ReleaseFailed");
    assertThat(handle(cmds.creditWallet(txn(), A, 1_000)).eventType()).isEqualTo("CreditRejected");
    assertThat(handle(cmds.reverse(txn(), txn(), 1_000)).eventType()).isEqualTo("ReversalRejected");
    CoreEvent validated = handle(cmds.validate(txn(), A, B, 1_000, "1234"));
    assertThat(validated.eventType()).isEqualTo("TransactionRejected");
    assertThat(validated.reasonCode()).isEqualTo("INJECTED_REJECT");
    // a status inquiry has no failure event: it is answered normally
    assertThat(handle(cmds.query(txn())).eventType()).isEqualTo("TransactionStatus");
  }

  @Test
  void aFaultScopedToATxnIdLeavesOtherTransactionsAlone() {
    String victim = txn();
    faults.add(fault(FaultMode.DROP_REPLY, null, victim, null, null, null));

    handle(cmds.post(victim, A, B, 1_000, "1234"));
    handle(cmds.post(txn(), A, B, 1_000, "1234"));

    assertThat(outbox()).isEqualTo(1);
  }

  @Test
  void aFaultWithATimesLimitFiresThatOftenAndThenStops() {
    faults.add(fault(FaultMode.DROP_REPLY, null, null, null, null, 2));

    handle(cmds.post(txn(), A, B, 1_000, "1234"));
    handle(cmds.post(txn(), A, B, 1_000, "1234"));
    handle(cmds.post(txn(), A, B, 1_000, "1234"));

    assertThat(outbox()).isEqualTo(1);
    assertThat(faults.list()).isEmpty();
  }

  @Test
  void resetClearsEveryFault() {
    faults.add(fault(FaultMode.DROP_REPLY, null, null, null, null, null));
    faults.add(fault(FaultMode.REJECT, null, null, null, null, null));
    assertThat(faults.list()).hasSize(2);

    faults.reset();

    assertThat(faults.list()).isEmpty();
    assertThat(handle(cmds.post(txn(), A, B, 1_000, "1234")).eventType()).isEqualTo("TransferPosted");
    assertThat(outbox()).isEqualTo(1);
  }

  @Test
  void aRedeliveredCommandAfterADroppedReplyGetsTheStoredEventWhenTheFaultIsGone() {
    faults.add(fault(FaultMode.DROP_REPLY, null, null, null, null, 1));
    CoreCommand post = cmds.post(txn(), A, B, 1_000, "1234");
    CoreEvent first = handle(post);
    assertThat(outbox()).isZero();

    CoreEvent again = handle(post); // the orchestrator re-sends after its timeout

    assertThat(again).isEqualTo(first);
    assertThat(outbox()).isEqualTo(1);
    assertThat(balance(A)).isEqualTo(1_000_000 - 1_500);
  }
}
