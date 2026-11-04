package mfs.mw.e2e.steps;

import static org.assertj.core.api.Assertions.assertThat;

import io.cucumber.java.After;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import mfs.mw.e2e.support.AuditTrail;
import mfs.mw.e2e.support.Http;
import mfs.mw.e2e.support.PartnerCallbacks;
import mfs.mw.e2e.support.Simulators;
import mfs.mw.e2e.support.Waits;

/**
 * Partner callbacks (ticket 22): the aggregator simulator in callback-only mode, callbacks sent by
 * the suite itself through the edge (signed with the dev secret, or badly), and what the audit
 * trail records about callbacks that came after the transaction was resolved.
 */
public class CallbackSteps {

  private static final Set<String> CALLBACK_ACTIONS = Set.of("partner.callback.late", "txn.outcome.conflict");

  private final ScenarioState state;
  private boolean aggregatorForced;
  private Http.Response callbackAnswer;
  private int replayStatus;

  public CallbackSteps(ScenarioState state) {
    this.state = state;
  }

  @Given("the aggregator confirms payments only by callback")
  public void callbackOnce() {
    force("ONCE", 0);
  }

  @Given("the aggregator confirms payments only by callback, sending each callback twice")
  public void callbackTwice() {
    force("DUPLICATE", 0);
  }

  @Given("the aggregator confirms payments only by callback, out of order")
  public void callbackOutOfOrder() {
    force("OUT_OF_ORDER", 0);
  }

  @Given("the aggregator confirms payments only by callback, {int} seconds late")
  public void callbackLate(int seconds) {
    force("LATE", seconds * 1000L);
  }

  private void force(String mode, long delayMs) {
    PartnerCallbacks.callbackOnly(mode, delayMs);
    aggregatorForced = true;
  }

  @When("the aggregator replays the transaction's callback")
  public void replay() {
    replayStatus = Simulators.replayCallback("billagg", PartnerCallbacks.reference(state.txnId()));
  }

  @Then("the replayed callback was acknowledged")
  public void replayAcknowledged() {
    assertThat(replayStatus).isEqualTo(200);
  }

  @When("the aggregator calls back {word} with code {word} for the transaction")
  public void signedCallback(String status, String code) {
    callbackAnswer =
        PartnerCallbacks.sendSigned(PartnerCallbacks.body(PartnerCallbacks.reference(state.txnId()), status, code, null));
  }

  @When("someone calls back {word} with code {word} for the transaction with a bad signature")
  public void forgedCallback(String status, String code) {
    callbackAnswer =
        PartnerCallbacks.send(
            PartnerCallbacks.body(PartnerCallbacks.reference(state.txnId()), status, code, null), "not-the-partner-secret");
  }

  @Then("the callback is answered {int}")
  public void callbackAnswered(int status) {
    assertThat(callbackAnswer.status()).as("%s", callbackAnswer).isEqualTo(status);
  }

  @Then("the callback is answered {int} with error code {string}")
  public void callbackAnsweredWithError(int status, String code) {
    callbackAnswered(status);
    assertThat(callbackAnswer.body().path("code").asString(null)).as("%s", callbackAnswer).isEqualTo(code);
  }

  @Then("the audit trail of the transaction records a late {word} callback within {int} seconds")
  public void lateCallbackAudited(String outcome, int seconds) {
    assertCallbackAudit("partner.callback.late", "SUCCESS", outcome, seconds);
  }

  @Then("the audit trail of the transaction records a conflicting {word} callback within {int} seconds")
  public void conflictAudited(String outcome, int seconds) {
    assertCallbackAudit("txn.outcome.conflict", "FAILURE", outcome, seconds);
  }

  private void assertCallbackAudit(String action, String auditOutcome, String callbackOutcome, int seconds) {
    String txnId = state.txnId();
    List<AuditTrail.Row> rows =
        Waits.until(
            () -> callbackRows(txnId),
            r -> r.stream().anyMatch(x -> matches(x, action, callbackOutcome)),
            Duration.ofSeconds(seconds));
    assertThat(rows).as("callback audit rows of %s", txnId).anySatisfy(r -> {
      assertThat(r.action()).isEqualTo(action);
      assertThat(r.outcome()).isEqualTo(auditOutcome);
      assertThat(r.actorType()).isEqualTo("partner");
      assertThat(r.actorId()).isEqualTo("billagg");
      assertThat(Http.JSON.readTree(r.after()).path("callbackOutcome").asString()).isEqualTo(callbackOutcome);
    });
  }

  /** Waits a little (audit is asynchronous) and checks no callback was recorded as late. */
  @Then("the audit trail of the transaction records no late callback")
  public void noLateCallback() {
    String txnId = state.txnId();
    List<AuditTrail.Row> rows = Waits.until(() -> callbackRows(txnId), r -> !r.isEmpty(), Duration.ofSeconds(5));
    assertThat(rows).as("callback audit rows of %s", txnId).isEmpty();
  }

  @Then("the audit trail of the transaction records the states {string} once each")
  public void statesOnce(String states) {
    List<String> expected = java.util.Arrays.stream(states.split(",")).map(String::strip).toList();
    String txnId = state.txnId();
    List<AuditTrail.Row> rows =
        Waits.until(() -> stateRows(txnId), r -> r.size() >= expected.size(), Duration.ofSeconds(30));
    assertThat(rows).extracting(AuditTrail.Row::afterStatus).as("audit rows of %s", txnId).containsExactlyElementsOf(expected);
  }

  private static boolean matches(AuditTrail.Row r, String action, String callbackOutcome) {
    return action.equals(r.action())
        && callbackOutcome.equals(Http.JSON.readTree(r.after()).path("callbackOutcome").asString(null));
  }

  private static List<AuditTrail.Row> callbackRows(String txnId) {
    return AuditTrail.rowsOfTxn(txnId).stream().filter(r -> CALLBACK_ACTIONS.contains(r.action())).toList();
  }

  private static List<AuditTrail.Row> stateRows(String txnId) {
    return AuditTrail.rowsOfTxn(txnId).stream().filter(r -> "txn.state.changed".equals(r.action())).toList();
  }

  // --- C2W bank (ticket 23) ------------------------------------------------------------------

  private boolean bankForced;

  @Given("the bank confirms debits by callback")
  public void bankCallbackOnce() {
    PartnerCallbacks.bankCallbacks("ONCE", 0);
    bankForced = true;
  }

  @Given("the bank confirms debits by callback, sending each callback twice")
  public void bankCallbackTwice() {
    PartnerCallbacks.bankCallbacks("DUPLICATE", 0);
    bankForced = true;
  }

  @When("the bank replays the transaction's callback")
  public void bankReplay() {
    replayStatus = Simulators.replayCallback(PartnerCallbacks.BANK, PartnerCallbacks.reference(state.txnId()));
  }

  @After
  public void resetAggregator() {
    if (aggregatorForced) {
      Simulators.reset("billagg");
    }
    if (bankForced) {
      Simulators.reset(PartnerCallbacks.BANK);
    }
  }
}
