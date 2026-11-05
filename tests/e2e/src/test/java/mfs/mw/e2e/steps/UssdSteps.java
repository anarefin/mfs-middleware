package mfs.mw.e2e.steps;

import static org.assertj.core.api.Assertions.assertThat;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import mfs.mw.e2e.support.UssdGateway;
import mfs.mw.e2e.support.Waits;

/**
 * USSD over the HTTP transport (ticket 26): the gateway simulator plays the MNO. Inputs are one
 * string, comma separated ({@code "9, 1, 01711000002, 10, 1, 1234"}); a {@code \n} in an expected
 * screen is a line break.
 */
public class UssdSteps {

  private final UssdState state;
  private final ScenarioState scenario;

  public UssdSteps(UssdState state, ScenarioState scenario) {
    this.state = state;
    this.scenario = scenario;
  }

  @When("{word} dials {word} on {word} and enters {string}")
  public void dial(String msisdn, String serviceCode, String mno, String inputs) {
    List<String> steps = inputs.isBlank() ? List.of() : Arrays.stream(inputs.split(",")).map(String::trim).toList();
    state.session = UssdGateway.dial(mno, msisdn, serviceCode, steps);
    assertThat(state.session.screens()).allSatisfy(s -> assertThat(s.status()).as("HTTP status of a step").isEqualTo(200));
  }

  // --- ticket 39: a session running while a new menu version goes live ---------------------------

  @Given("{word} has a USSD session open on {word} after dialling {word}")
  public void openSession(String msisdn, String mno, String serviceCode) {
    state.openSession = UssdGateway.dial(mno, msisdn, serviceCode, List.of());
    state.openMno = mno;
    state.openMsisdn = msisdn;
    assertThat(state.openSession.ended()).as("session open, screens %s", state.openSession.screens()).isFalse();
  }

  @When("the open USSD session continues with {string}")
  public void continueOpen(String input) {
    UssdGateway.Session open = state.openSession;
    assertThat(open).as("a session was left open").isNotNull();
    state.session = UssdGateway.resume(state.openMno, state.openMsisdn, "*268#", open.sessionId(), List.of(input));
  }

  /** The English main menu, without the title the scenario just published (so: the old version). */
  @Then("the open USSD session shows the English main menu without {string}")
  public void startedVersion(String newTitle) {
    UssdGateway.Session s = state.session();
    assertThat(s.last().status()).isEqualTo(200);
    String title = newTitle.replace("{token}", scenario.token);
    assertThat(s.last().message()).as("the version the session started on").contains("\n1 Send Money").doesNotContain(title);
  }

  @Then("a new USSD session of {word} on {word} starts with {string} within {int} seconds")
  public void newSessionStartsWith(String msisdn, String mno, String text, int seconds) {
    String expected = unescape(text.replace("{token}", scenario.token));
    UssdGateway.Session s =
        Waits.until(() -> UssdGateway.dial(mno, msisdn, "*268#", List.of()),
            x -> x.last().message() != null && x.last().message().startsWith(expected), Duration.ofSeconds(seconds));
    assertThat(s.last().message()).startsWith(expected);
  }

  @Then("the USSD session ends with {string}")
  public void endsWith(String text) {
    UssdGateway.Session s = state.session();
    assertThat(s.ended()).as("session ended, screens " + s.screens()).isTrue();
    assertThat(s.last().flag()).isEqualTo("END");
    assertThat(s.last().message()).startsWith(unescape(text));
  }

  @Then("the USSD session is still open on {string}")
  public void openOn(String text) {
    UssdGateway.Session s = state.session();
    assertThat(s.ended()).as("session still open, screens " + s.screens()).isFalse();
    assertThat(s.last().flag()).isEqualTo("CON");
    assertThat(s.last().message()).isEqualTo(unescape(text));
  }

  @Then("the USSD session is still open on a screen ending with {string}")
  public void openOnEndingWith(String text) {
    UssdGateway.Session s = state.session();
    assertThat(s.ended()).as("session still open, screens " + s.screens()).isFalse();
    assertThat(s.last().flag()).isEqualTo("CON");
    assertThat(s.last().message()).endsWith(unescape(text));
  }

  @Then("the first USSD screens were:")
  public void screensWere(List<String> expected) {
    List<String> shown = state.session().screens().stream().map(UssdGateway.Screen::message).toList();
    assertThat(shown).hasSizeGreaterThanOrEqualTo(expected.size());
    assertThat(shown.subList(0, expected.size()))
        .containsExactlyElementsOf(expected.stream().map(UssdSteps::unescape).toList());
  }

  private static String unescape(String text) {
    return text.replace("\\n", "\n");
  }
}
