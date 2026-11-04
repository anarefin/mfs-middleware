package mfs.mw.e2e.steps;

import static org.assertj.core.api.Assertions.assertThat;

import io.cucumber.java.After;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mfs.mw.e2e.support.CoreMock;
import mfs.mw.e2e.support.Http;
import tools.jackson.databind.node.ObjectNode;

/**
 * Steps on the core's ledger (mw-core-mock's test API): note balances before an action, then
 * assert the exact change, which proves money moved once (or not at all).
 */
public class CoreSteps {

  private final ScenarioState state;

  public CoreSteps(ScenarioState state) {
    this.state = state;
  }

  @Given("the core balances of these wallets are noted:")
  public void noteBalances(List<String> wallets) {
    for (String w : wallets) {
      state.notedBalances.put(w, CoreMock.balance(w));
    }
  }

  /** One-shot REJECT fault: the next command of this type (any txn) fails with this reason. */
  @Given("the core rejects the next {word} with reason {word}")
  public void rejectsNext(String commandType, String reasonCode) {
    ObjectNode fault = Http.JSON.createObjectNode();
    fault.put("mode", "REJECT").put("commandType", commandType).put("reasonCode", reasonCode).put("times", 1);
    state.faultInjected = true;
    CoreMock.injectFault(fault);
  }

  @After
  public void resetFaults() {
    if (state.faultInjected) {
      CoreMock.resetFaults();
    }
  }

  @Then("the core balances changed by:")
  public void balancesChangedBy(Map<String, Long> expectedDeltas) {
    assertThat(deltas()).isEqualTo(new LinkedHashMap<>(expectedDeltas));
  }

  @Then("no core balance changed")
  public void noBalanceChanged() {
    Map<String, Long> none = new LinkedHashMap<>();
    state.notedBalances.keySet().forEach(w -> none.put(w, 0L));
    assertThat(deltas()).isEqualTo(none);
  }

  /** Ledger balance now minus the noted one, for every noted wallet. */
  private Map<String, Long> deltas() {
    if (state.notedBalances.isEmpty()) {
      throw new IllegalStateException("no balances were noted before the action");
    }
    Map<String, Long> deltas = new LinkedHashMap<>();
    state.notedBalances.forEach(
        (w, before) -> deltas.put(w, CoreMock.balance(w).balanceMinor() - before.balanceMinor()));
    return deltas;
  }
}
