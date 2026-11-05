package mfs.mw.e2e.steps;

import static org.assertj.core.api.Assertions.assertThat;

import io.cucumber.java.en.Then;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import mfs.mw.e2e.support.AuditTrail;
import mfs.mw.e2e.support.Waits;

/** Steps on the audit trail (mw-audit-dw): the states a transaction passed and the hash chain. */
public class AuditSteps {

  private final ScenarioState state;

  public AuditSteps(ScenarioState state) {
    this.state = state;
  }

  @Then("the audit trail of the transaction records the states {string}")
  public void recordsStates(String states) {
    List<String> expected = Arrays.stream(states.split(",")).map(String::strip).toList();
    String txnId = state.txnId();
    // audited asynchronously (outbox -> Kafka -> mw-audit-dw)
    List<AuditTrail.Row> rows =
        Waits.until(() -> AuditTrail.stateRowsOfTxn(txnId), r -> r.size() >= expected.size(), Duration.ofSeconds(30));
    assertThat(rows).as("audit rows of %s", txnId).allSatisfy(r -> {
      assertThat(r.action()).isEqualTo("txn.state.changed");
      assertThat(r.resource()).isEqualTo("txn/" + txnId);
    });
    assertThat(rows).extracting(AuditTrail.Row::afterStatus).as("audit rows of %s", txnId).containsExactlyElementsOf(expected);
  }

  /** S6: the adapter audits its partner call, masked, on the same trail. */
  @Then("the audit trail of the transaction records the partner call by {word}")
  public void recordsPartnerCall(String service) {
    String txnId = state.txnId();
    List<AuditTrail.Row> rows =
        Waits.until(
            () -> AuditTrail.rowsOfTxn(txnId).stream().filter(r -> AuditTrail.PARTNER_CALL.equals(r.action())).toList(),
            r -> !r.isEmpty(),
            Duration.ofSeconds(30));
    assertThat(rows).as("partner call audit rows of %s", txnId).isNotEmpty().allSatisfy(r -> {
      assertThat(r.service()).isEqualTo(service);
      assertThat(r.actorType()).isEqualTo("system");
      assertThat(r.resource()).startsWith("partner/").endsWith("/PAY");
      assertThat(r.after()).contains("partnerOutcome");
    });
  }

  @Then("the audit hash chain of the transaction's month verifies")
  public void chainVerifies() {
    List<AuditTrail.Row> rows = AuditTrail.rowsOfTxn(state.txnId());
    assertThat(rows).as("audit rows of %s", state.txnId()).isNotEmpty();
    for (String month : rows.stream().map(AuditTrail.Row::month).distinct().toList()) {
      assertThat(AuditTrail.verifyChain(month)).as("hash chain of %s", month).isNull();
    }
  }
}
