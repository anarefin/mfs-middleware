package mfs.mw.e2e.steps;

import static org.assertj.core.api.Assertions.assertThat;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.time.Duration;
import java.util.List;
import mfs.mw.e2e.support.AuditTrail;
import mfs.mw.e2e.support.CoreMock;
import mfs.mw.e2e.support.Http;
import mfs.mw.e2e.support.Stack;
import mfs.mw.e2e.support.TxnRequests;
import mfs.mw.e2e.support.Waits;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reversal (ticket 19): operations asks for the compensation of a completed transaction with a
 * REVERSAL request on {@code mw.txn.request.v1} (the admin console's maker-checker trigger is
 * ticket 37; until then the suite publishes the request itself). The original transaction then
 * shows REVERSING and REVERSED (or MANUAL_REVIEW) in the app's status view.
 */
public class ReversalSteps {

  private final ScenarioState state;

  public ReversalSteps(ScenarioState state) {
    this.state = state;
  }

  /** Reverses the scenario's transaction with a fresh idempotency key, for its full amount. */
  @When("operations reverses the transaction")
  public void reverses() {
    JsonNode original = state.responses.getFirst().body();
    assertThat(original.path("status").asString(null)).as("%s", original).isEqualTo("COMPLETED");
    ObjectNode request =
        TxnRequests.reversal(
            TxnRequests.uuidV7(),
            "e2e-rev-" + TxnRequests.uuidV7(),
            state.txnId(),
            original.path("debitParty").path("id").asString(),
            original.path("creditParty").path("id").asString(),
            original.path("amount").path("valueMinor").asLong());
    TxnRequests.publish(request);
    state.reversalKeys.add(request.path("idempotencyKey").asString());
  }

  /**
   * The orchestrator has taken the last reversal request in: its idempotency key is recorded
   * (read with the superuser, test-only; the request leaves no other trace when it changes nothing).
   */
  @Then("the orchestrator has handled the reversal request")
  public void handled() {
    String key = state.reversalKeys.getLast();
    if (!key.matches("[a-z0-9-]+")) {
      throw new IllegalStateException("unexpected key " + key); // keeps the SQL literal safe
    }
    String count =
        Waits.until(
            () -> Stack.get().psql("select count(*) from txn.idempotency where idem_key = '" + key + "'"),
            "1"::equals,
            Duration.ofSeconds(30));
    assertThat(count).as("idempotency row of %s", key).isEqualTo("1");
  }

  /** The core refuses the next {@code commandType} without effect (fault REJECT, once). */
  @Given("the core rejects the next {word} with reason {word}")
  public void coreRejects(String commandType, String reason) {
    ObjectNode fault = Http.JSON.createObjectNode();
    fault.put("mode", "REJECT").put("commandType", commandType).put("reasonCode", reason).put("times", 1);
    CoreMock.injectFault(fault);
    state.faultsInjected = true;
  }

  @Then("the audit trail of the transaction records {word} with error code {string}")
  public void auditRecordsError(String status, String code) {
    String txnId = state.txnId();
    List<AuditTrail.Row> rows =
        Waits.until(
            () -> AuditTrail.rowsOfTxn(txnId),
            r -> r.stream().anyMatch(x -> status.equals(x.afterStatus())),
            Duration.ofSeconds(30));
    AuditTrail.Row row =
        rows.stream().filter(x -> status.equals(x.afterStatus())).findFirst().orElseThrow(
            () -> new AssertionError("no audit row with status " + status + " for " + txnId + ": " + rows));
    JsonNode after = Http.JSON.readTree(row.after());
    assertThat(after.path("errorCode").asString(null)).as("%s", row).isEqualTo(code);
  }
}
