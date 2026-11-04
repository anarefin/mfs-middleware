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

  /** The core applies the next {@code commandType} but never answers it (fault DROP_REPLY, once). */
  @Given("the core drops its reply to the next {word}")
  public void dropReply(String commandType) {
    ObjectNode fault = Http.JSON.createObjectNode();
    fault.put("mode", "DROP_REPLY").put("commandType", commandType).put("times", 1);
    CoreMock.injectFault(fault);
    state.faultsInjected = true;
  }

  @After
  public void resetFaults() {
    if (state.faultsInjected) {
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

  /**
   * The wallet's held amount is what it was when noted: a hold placed by the scenario was
   * committed or released, never left behind.
   */
  @Then("no funds are held on {word}")
  public void noFundsHeld(String wallet) {
    CoreMock.Balance before = state.notedBalances.get(wallet);
    if (before == null) {
      throw new IllegalStateException("the balance of " + wallet + " was not noted before the action");
    }
    assertThat(CoreMock.balance(wallet).heldMinor()).as("held on %s", wallet).isEqualTo(before.heldMinor());
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
