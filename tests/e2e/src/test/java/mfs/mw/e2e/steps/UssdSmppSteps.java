package mfs.mw.e2e.steps;

import static org.assertj.core.api.Assertions.assertThat;

import io.cucumber.java.After;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.time.Duration;
import mfs.mw.e2e.support.UssdGateway;
import mfs.mw.e2e.support.Waits;

/**
 * USSD over SMPP (ticket 27): the gateway simulator plays gp over SMPP and mw-ussd holds its
 * transceiver binds. The menu walk itself uses {@link UssdSteps} with {@code on gp}.
 */
public class UssdSmppSteps {

  private int bindsBefore;
  private int refusedBefore;
  private boolean scenarioSet;

  @After("@smpp")
  public void resetScenario() {
    if (scenarioSet) {
      UssdGateway.smppScenario("{}");
    }
  }

  @Given("mw-ussd is bound to the SMPP USSD gateway")
  public void bound() {
    UssdGateway.SmppBinds b =
        Waits.until(UssdGateway::smppBinds, s -> s.bound() >= 1, Duration.ofSeconds(90));
    assertThat(b.bound()).isPositive();
    bindsBefore = b.binds();
    refusedBefore = b.refused();
  }

  @Then("mw-ussd keeps its SMPP binds alive with enquire_link")
  public void enquireLinks() {
    int before = UssdGateway.smppBinds().enquireLinks();
    // enquire_link every 30 s per bind
    UssdGateway.SmppBinds b =
        Waits.until(UssdGateway::smppBinds, s -> s.enquireLinks() > before, Duration.ofSeconds(45));
    assertThat(b.enquireLinks()).isGreaterThan(before);
  }

  @When("the SMPP USSD gateway drops every bind")
  public void drop() {
    UssdGateway.dropSmppBinds();
  }

  @Then("mw-ussd binds to the SMPP USSD gateway again")
  public void rebound() {
    UssdGateway.SmppBinds b =
        Waits.until(UssdGateway::smppBinds, s -> s.binds() > bindsBefore && s.bound() >= 1, Duration.ofSeconds(90));
    assertThat(b.bound()).isPositive();
  }

  @Given("the SMPP USSD gateway refuses the next screens with {string}")
  public void refuse(String kinds) {
    int throttle = 0;
    int queueFull = 0;
    int nack = 0;
    for (String k : kinds.split(",")) {
      switch (k.trim()) {
        case "throttled" -> throttle++;
        case "queue full" -> queueFull++;
        case "generic_nack" -> nack++;
        default -> throw new IllegalArgumentException("unknown refusal " + k);
      }
    }
    scenarioSet = true;
    UssdGateway.smppScenario("{\"throttleNext\":" + throttle + ",\"queueFullNext\":" + queueFull
        + ",\"genericNackNext\":" + nack + "}");
  }

  @Then("the SMPP USSD gateway refused {int} screens")
  public void refused(int n) {
    assertThat(UssdGateway.smppBinds().refused() - refusedBefore).isEqualTo(n);
  }
}
