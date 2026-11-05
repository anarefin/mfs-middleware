package mfs.mw.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import mfs.mw.orchestrator.MutableClock;
import mfs.mw.orchestrator.TestData;
import mfs.mw.orchestrator.TestInfrastructure;
import mfs.mw.orchestrator.dto.TxnRequest;
import mfs.mw.orchestrator.exception.InvalidTransitionException;
import mfs.mw.orchestrator.repository.TransactionRepository;
import mfs.mw.orchestrator.service.saga.SagaEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The operator's exits from MANUAL_REVIEW (ManualCommit / ManualRelease, spec 7.2) through {@link
 * SagaEngine#fire}: a decision whose step the flow does not have (P2P, CASH_IN and CASH_OUT post
 * in one step, ADD_MONEY_BANK holds nothing to release) is refused as an invalid transition, so
 * nothing is written and no timer is armed; a decision the flow can carry out sends its command.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {"mw.orchestrator.consumers.enabled=false", "mw.orchestrator.timer.enabled=false"})
@Import({TestInfrastructure.class, SagaServiceIT.TestClock.class})
class ManualReviewExitIT {

  private final TransactionService intake;
  private final TransactionRepository transactions;
  private final SagaEngine engine;
  private final MutableClock clock;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final mfs.mw.orchestrator.scheduler.ManualReviewAgeingJob ageing;
  private final io.micrometer.core.instrument.MeterRegistry meters;
  private final CoreEventService coreEvents;
  private final tools.jackson.databind.json.JsonMapper json;

  @Autowired
  ManualReviewExitIT(
      TransactionService intake,
      TransactionRepository transactions,
      SagaEngine engine,
      Clock clock,
      JdbcTemplate jdbc,
      TransactionTemplate tx,
      mfs.mw.orchestrator.scheduler.ManualReviewAgeingJob ageing,
      io.micrometer.core.instrument.MeterRegistry meters,
      CoreEventService coreEvents,
      tools.jackson.databind.json.JsonMapper json) {
    this.coreEvents = coreEvents;
    this.json = json;
    this.intake = intake;
    this.transactions = transactions;
    this.engine = engine;
    this.clock = (MutableClock) clock;
    this.jdbc = jdbc;
    this.tx = tx;
    this.ageing = ageing;
    this.meters = meters;
  }

  @BeforeEach
  void clean() {
    clock.set(TestData.NOW);
    for (String t : List.of("outbox", "inbox", "saga_timer", "saga_step", "idempotency", "transaction")) {
      jdbc.update("delete from " + t);
    }
  }

  private static TxnRequest ofType(TxnRequest base, String txnType) {
    return new TxnRequest(
        base.schemaVersion(), base.txnId(), base.idempotencyKey(), base.correlationId(), base.channel(), txnType,
        base.initiator(), base.debitParty(), base.creditParty(), base.amount(), base.partner(), base.originalTxnId(),
        base.encryptedPin(), base.locale(), base.metadata(), base.attributes(), base.requestedAt());
  }

  /** Accepts {@code req} and parks it in MANUAL_REVIEW, as an operator would find it. */
  private UUID inManualReview(TxnRequest req) {
    intake.accept(req, "gw-1");
    UUID txnId = UUID.fromString(req.txnId());
    jdbc.update("update transaction set status = 'MANUAL_REVIEW', hold_id = null where txn_id = ?", txnId);
    jdbc.update("delete from saga_timer");
    jdbc.update("delete from outbox");
    return txnId;
  }

  private void decide(UUID txnId, SagaEvent decision) {
    tx.executeWithoutResult(s -> engine.fire(transactions.findForUpdate(txnId).orElseThrow(), decision, null));
  }

  private void assertRefusedUntouched(UUID txnId, SagaEvent decision) {
    assertThatThrownBy(() -> decide(txnId, decision)).isInstanceOf(InvalidTransitionException.class);
    assertThat(jdbc.queryForObject("select status from transaction where txn_id = ?", String.class, txnId))
        .isEqualTo("MANUAL_REVIEW");
    assertThat(jdbc.queryForObject("select count(*) from saga_timer", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from outbox", Integer.class)).isZero();
  }

  @Test
  void flowsThatPostInOneStepRefuseManualCommitAndRelease() {
    for (String type : List.of("P2P", "CASH_IN", "CASH_OUT", "MERCHANT_PAY")) {
      String id = TestData.txnId();
      UUID txnId = inManualReview(ofType(TestData.p2p(id, "key-" + id, 5_000), type));

      assertRefusedUntouched(txnId, new SagaEvent.ManualCommit());
      assertRefusedUntouched(txnId, new SagaEvent.ManualRelease());
    }
  }

  @Test
  void addMoneyBankRefusesManualReleaseAndAsksTheCoreBeforeAForcedCredit() {
    String id = TestData.txnId();
    UUID txnId = inManualReview(TestData.addMoneyBank(id, "key-" + id, 5_000, "1234567890123"));

    assertRefusedUntouched(txnId, new SagaEvent.ManualRelease());

    decide(txnId, new SagaEvent.ManualCommit());
    assertThat(status(txnId)).isEqualTo("COMMITTING");
    assertThat(commandTypes()).containsExactly("QueryTransaction");
    assertThat(lastCommandId()).startsWith(id + ":credit-check:");
  }

  @Test
  void aForcedCreditIsSentOnlyOnceTheCoreSaysTheTxnIsNotCredited() {
    String id = TestData.txnId();
    UUID txnId = inManualReview(TestData.addMoneyBank(id, "key-" + id, 5_000, "1234567890123"));
    decide(txnId, new SagaEvent.ManualCommit());

    coreEvents.handle(event("TransactionStatus", id, lastCommandId(), "{\"txnStatus\":\"REJECTED\"}"));

    assertThat(status(txnId)).isEqualTo("COMMITTING");
    assertThat(commandTypes()).containsExactly("QueryTransaction", "CreditWallet");
    assertThat(lastCommandId()).isEqualTo(id + ":credit:manual-1");

    coreEvents.handle(event("WalletCredited", id, id + ":credit:manual-1", "{}"));
    assertThat(status(txnId)).isEqualTo("COMPLETED");
  }

  @Test
  void aForcedCreditOfATxnTheCoreAlreadyCreditedCompletesWithoutANewCredit() {
    String id = TestData.txnId();
    UUID txnId = inManualReview(TestData.addMoneyBank(id, "key-" + id, 5_000, "1234567890123"));
    decide(txnId, new SagaEvent.ManualCommit());

    coreEvents.handle(event("TransactionStatus", id, lastCommandId(), "{\"txnStatus\":\"CREDITED\"}"));

    assertThat(status(txnId)).isEqualTo("COMPLETED");
    assertThat(commandTypes()).containsExactly("QueryTransaction");
  }

  @Test
  void aCreditCheckAnswerThatFitsNoOutcomeHandsTheTxnBackWithoutACredit() {
    String id = TestData.txnId();
    UUID txnId = inManualReview(TestData.addMoneyBank(id, "key-" + id, 5_000, "1234567890123"));
    decide(txnId, new SagaEvent.ManualCommit());

    coreEvents.handle(event("TransactionStatus", id, lastCommandId(), "{\"txnStatus\":\"REVERSED\"}"));

    assertThat(status(txnId)).isEqualTo("MANUAL_REVIEW");
    assertThat(commandTypes()).containsExactly("QueryTransaction");
  }

  @Test
  void aForcedCreditDeadlineAsksTheCoreAgainRatherThanResendingBlind() {
    String id = TestData.txnId();
    UUID txnId = inManualReview(TestData.addMoneyBank(id, "key-" + id, 5_000, "1234567890123"));
    decide(txnId, new SagaEvent.ManualCommit());
    coreEvents.handle(event("TransactionStatus", id, lastCommandId(), "{\"txnStatus\":\"NOT_FOUND\"}"));
    clock.set(TestData.NOW.plusSeconds(1));

    decide(txnId, new SagaEvent.DeadlineExpired());

    assertThat(commandTypes()).containsExactly("QueryTransaction", "CreditWallet", "QueryTransaction");
  }

  @Test
  void aLateAnswerToAnOlderCreditCheckNeitherCreditsNorCompletesTheCurrentRound() {
    String id = TestData.txnId();
    UUID txnId = inManualReview(TestData.addMoneyBank(id, "key-" + id, 5_000, "1234567890123"));
    decide(txnId, new SagaEvent.ManualCommit());
    String firstCheck = lastCommandId();
    coreEvents.handle(event("TransactionStatus", id, firstCheck, "{\"txnStatus\":\"NOT_FOUND\"}"));
    coreEvents.handle(event("CreditRejected", id, id + ":credit:manual-1", "{}"));
    assertThat(status(txnId)).isEqualTo("MANUAL_REVIEW");
    clock.set(TestData.NOW.plusSeconds(30));
    decide(txnId, new SagaEvent.ManualCommit());
    String secondCheck = lastCommandId();
    assertThat(secondCheck).isNotEqualTo(firstCheck).startsWith(id + ":credit-check:");

    coreEvents.handle(event("TransactionStatus", id, firstCheck, "{\"txnStatus\":\"NOT_FOUND\"}"));
    coreEvents.handle(event("TransactionStatus", id, firstCheck, "{\"txnStatus\":\"CREDITED\"}"));

    assertThat(status(txnId)).isEqualTo("COMMITTING");
    assertThat(commandTypes()).containsExactly("QueryTransaction", "CreditWallet", "QueryTransaction");

    coreEvents.handle(event("TransactionStatus", id, secondCheck, "{\"txnStatus\":\"REJECTED\"}"));
    assertThat(lastCommandId()).isEqualTo(id + ":credit:manual-2");
  }

  private String status(UUID txnId) {
    return jdbc.queryForObject("select status from transaction where txn_id = ?", String.class, txnId);
  }

  private List<String> commandTypes() {
    return jdbc.queryForList(
        "select payload->>'commandType' from outbox where topic = 'core.wallet.command.v1' order by id", String.class);
  }

  private String lastCommandId() {
    List<String> ids = jdbc.queryForList(
        "select payload->>'commandId' from outbox where topic = 'core.wallet.command.v1' order by id", String.class);
    return ids.get(ids.size() - 1);
  }

  private mfs.mw.orchestrator.dto.CoreEvent event(String type, String txnId, String commandId, String payload) {
    return json.readValue(
        "{\"schemaVersion\":1,\"eventType\":\"" + type + "\",\"eventId\":\"evt-" + UUID.randomUUID()
            + "\",\"commandId\":\"" + commandId + "\",\"txnId\":\"" + txnId
            + "\",\"coreRef\":\"CORE-7\",\"occurredAt\":\"2026-11-03T08:15:31Z\",\"outcome\":\"SUCCESS\""
            + ",\"payload\":" + payload + "}",
        mfs.mw.orchestrator.dto.CoreEvent.class);
  }

  @Test
  void theDailyAgeingCheckMeasuresTheOldestManualReview() {
    String a = TestData.txnId();
    UUID first = inManualReview(TestData.p2p(a, "key-" + a, 5_000));
    String b = TestData.txnId();
    inManualReview(TestData.p2p(b, "key-" + b, 5_000));
    jdbc.update("update transaction set updated_at = ? where txn_id = ?",
        java.sql.Timestamp.from(TestData.NOW.minus(java.time.Duration.ofHours(30))), first);

    ageing.check();

    assertThat(meters.find("mw.manual.review.oldest.age").gauge().value()).isEqualTo(30 * 3600);
  }
}
