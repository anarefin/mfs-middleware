package mfs.mw.e2e.steps;

import io.cucumber.java.After;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import mfs.mw.e2e.support.Http;
import mfs.mw.e2e.support.Simulators;
import tools.jackson.databind.node.ObjectNode;

/** Steps that force a partner simulator's behaviour (mw-simulators control API). */
public class SimulatorSteps {

  private final ScenarioState state;

  public SimulatorSteps(ScenarioState state) {
    this.state = state;
  }

  /**
   * The OCS holds every top-up 25 s (past the adapter's 20 s time limit, so the adapter reports
   * UNKNOWN) and then refuses it with a 503 without applying it: a timeout the top-up did not
   * survive. Its status inquiry then answers NOT_FOUND once the scenario is reset.
   */
  @Given("the OCS answers too late and never applies the top-up")
  public void ocsTimesOutWithoutApplying() {
    ObjectNode scenario = Http.JSON.createObjectNode();
    // every field of the simulator's Scenario record (its primitives must be present)
    scenario.put("latencyMs", 25_000).put("errorRate", 1.0).put("outage", false)
        .put("callbackMode", "NONE").put("callbackDelayMs", 0);
    Simulators.setScenario("ocs", scenario);
    state.simulatorForced = true;
  }

  @Then("the OCS behaves normally again")
  public void ocsNormal() {
    Simulators.reset("ocs");
    state.simulatorForced = false;
  }

  @After
  public void resetSimulator() {
    if (state.simulatorForced) {
      Simulators.reset("ocs");
    }
  }
}
