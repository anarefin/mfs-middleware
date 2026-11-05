package mfs.mw.e2e.steps;

import static org.assertj.core.api.Assertions.assertThat;

import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.util.Arrays;
import java.util.List;
import mfs.mw.e2e.support.UssdGateway;

/**
 * USSD over the HTTP transport (ticket 26): the gateway simulator plays the MNO. Inputs are one
 * string, comma separated ({@code "9, 1, 01711000002, 10, 1, 1234"}); a {@code \n} in an expected
 * screen is a line break.
 */
public class UssdSteps {

  private final UssdState state;

  public UssdSteps(UssdState state) {
    this.state = state;
  }

  @When("{word} dials {word} on {word} and enters {string}")
  public void dial(String msisdn, String serviceCode, String mno, String inputs) {
    List<String> steps = inputs.isBlank() ? List.of() : Arrays.stream(inputs.split(",")).map(String::trim).toList();
    state.session = UssdGateway.dial(mno, msisdn, serviceCode, steps);
    assertThat(state.session.screens()).allSatisfy(s -> assertThat(s.status()).as("HTTP status of a step").isEqualTo(200));
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
