package mfs.mw.e2e.steps;

import static org.assertj.core.api.Assertions.assertThat;

import io.cucumber.java.After;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import mfs.mw.e2e.support.AdminApi;
import mfs.mw.e2e.support.Http;
import mfs.mw.e2e.support.Simulators;
import mfs.mw.e2e.support.Waits;
import tools.jackson.databind.node.ObjectNode;

/**
 * Partner config through maker-checker (ticket 38, spec 15.3, 20.4): ops-e2e-maker01 asks to
 * disable / enable a partner or change its timeouts, ops-e2e-checker01 approves, mw-admin-api
 * publishes the full config on mw.config.changed.v1 and the adapters swap it in without a restart.
 * Every partner changed is put back to its seed (enabled, 20 s) by an {@code @After} hook, so a
 * failing scenario does not leave the stack's OCS disabled.
 */
public class PartnerConfigSteps {

  static final String MAKER = "ops-e2e-maker01";
  static final String CHECKER = "ops-e2e-checker01";

  private final ScenarioState state;
  private final List<String> changed = new ArrayList<>();
  private long publishedVersion;

  public PartnerConfigSteps(ScenarioState state) {
    this.state = state;
  }

  @When("the operators disable partner {word}")
  public void disable(String partnerId) {
    change(partnerId, body("DISABLE", "e2e: partner incident"));
  }

  @When("the operators enable partner {word}")
  public void enable(String partnerId) {
    change(partnerId, body("ENABLE", "e2e: partner back"));
  }

  @When("the operators set the time limit of partner {word} to {long} ms")
  public void timeout(String partnerId, long timeoutMs) {
    ObjectNode b = body("SET_TIMEOUTS", "e2e: fail faster");
    b.putObject("timeouts").put("timeoutMs", timeoutMs);
    change(partnerId, b);
  }

  /**
   * The approval is published through the outbox relay and the adapters' config consumer: give
   * it a moment (no adapter endpoint shows the applied version).
   */
  @Then("the partner change has reached the adapters")
  public void reached() {
    Waits.pause(Duration.ofSeconds(5));
  }

  @Given("the OCS answers every top-up after {int} seconds")
  public void ocsSlow(int seconds) {
    ObjectNode scenario = Http.JSON.createObjectNode();
    scenario.put("latencyMs", seconds * 1000).put("errorRate", 0.0).put("outage", false)
        .put("callbackMode", "NONE").put("callbackDelayMs", 0);
    Simulators.setScenario("ocs", scenario);
    state.simulatorForced = true;
  }

  private void change(String partnerId, ObjectNode body) {
    Http.Response asked = AdminApi.askPartner(MAKER, partnerId, body);
    assertThat(asked.status()).as("%s", asked.body()).isEqualTo(201);
    if (!changed.contains(partnerId)) {
      changed.add(partnerId);
    }
    String id = asked.body().path("id").asString();
    Http.Response own = AdminApi.approve(MAKER, id);
    assertThat(own.status()).as("the maker cannot approve: %s", own.body()).isEqualTo(403);
    Http.Response approved = AdminApi.approve(CHECKER, id);
    assertThat(approved.status()).as("%s", approved.body()).isEqualTo(200);
    assertThat(approved.body().path("status").asString(null)).isEqualTo("APPROVED");
    long version = approved.body().path("payload").path("version").asLong();
    assertThat(version).isGreaterThan(publishedVersion);
    publishedVersion = version;
    assertThat(AdminApi.partner(CHECKER, partnerId).body().path("version").asLong()).isEqualTo(version);
  }

  private static ObjectNode body(String action, String reason) {
    return Http.JSON.createObjectNode().put("action", action).put("reason", reason);
  }

  /** Back to the seed: enabled, 20 s time limit (a change that changes nothing is a 409, ignored). */
  @After
  public void restore() {
    for (String partnerId : changed) {
      ObjectNode b = body("SET_TIMEOUTS", "e2e: restore");
      b.putObject("timeouts").put("timeoutMs", 20_000);
      for (ObjectNode restore : List.of(body("ENABLE", "e2e: restore"), b)) {
        Http.Response asked = AdminApi.askPartner(MAKER, partnerId, restore);
        if (asked.status() == 201) {
          AdminApi.approve(CHECKER, asked.body().path("id").asString());
        }
      }
    }
    if (!changed.isEmpty()) {
      Waits.pause(Duration.ofSeconds(5));
    }
  }
}
