package mfs.mw.e2e.steps;

import static org.assertj.core.api.Assertions.assertThat;

import io.cucumber.java.After;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import java.time.Duration;
import java.util.List;
import mfs.mw.e2e.support.Http;
import mfs.mw.e2e.support.Simulators;
import mfs.mw.e2e.support.Waits;
import tools.jackson.databind.JsonNode;
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

  /**
   * The aggregator holds every payment 28 s (past mw-adapter-billpay's 27 s read limit, so the
   * adapter reports UNKNOWN) and then refuses it with a 503 without applying it. Its status inquiry
   * answers NOT_FOUND once the scenario is reset.
   */
  @Given("the aggregator answers too late and never applies the payment")
  public void aggregatorTimesOutWithoutApplying() {
    lateAndUnapplied("billagg");
  }

  /** As the aggregator, for the C2W bank's debits (mw-adapter-bank's read limit is 27 s too). */
  @Given("the bank answers too late and never applies the debit")
  public void bankTimesOutWithoutApplying() {
    lateAndUnapplied("c2wbank");
  }

  /**
   * Waits until the aggregator has answered (late, 503) the payment sent since it was forced, so
   * the reset can never come before the PAY reached it, then resets it for the status inquiry.
   */
  @Then("the aggregator behaves normally again")
  public void aggregatorNormal() {
    normalOnceAnswered("billagg", "/billagg/v1/payments");
  }

  @Then("the bank behaves normally again")
  public void bankNormal() {
    normalOnceAnswered("c2wbank", "/c2wbank/v1/debits");
  }

  private void lateAndUnapplied(String partner) {
    long seq = Simulators.lastSeq(partner);
    ObjectNode scenario = Http.JSON.createObjectNode();
    scenario.put("latencyMs", 28_000).put("errorRate", 1.0).put("outage", false)
        .put("callbackMode", "NONE").put("callbackDelayMs", 0);
    Simulators.setScenario(partner, scenario);
    state.forcedPartners.put(partner, seq);
  }

  private void normalOnceAnswered(String partner, String payPath) {
    long since = state.forcedPartners.getOrDefault(partner, 0L);
    List<JsonNode> answered =
        Waits.until(
            () -> payments(partner, payPath, since),
            l -> !l.isEmpty(),
            Duration.ofSeconds(60));
    assertThat(answered).as("%s answered a %s since it was forced", partner, payPath).isNotEmpty();
    Simulators.reset(partner);
    state.forcedPartners.remove(partner);
  }

  private static List<JsonNode> payments(String partner, String payPath, long since) {
    return Simulators.requests(partner).stream()
        .filter(r -> r.path("seq").asLong() > since)
        .filter(r -> "POST".equals(r.path("method").asString(null)) && payPath.equals(r.path("path").asString(null)))
        .toList();
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
    state.forcedPartners.keySet().forEach(Simulators::reset);
  }
}
