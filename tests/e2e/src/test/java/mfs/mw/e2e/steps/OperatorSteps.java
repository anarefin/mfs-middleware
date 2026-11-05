package mfs.mw.e2e.steps;

import static org.assertj.core.api.Assertions.assertThat;

import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import mfs.mw.e2e.support.AdminApi;
import mfs.mw.e2e.support.Http;
import mfs.mw.e2e.support.Waits;
import tools.jackson.databind.JsonNode;

/**
 * Acceptance criterion 4 (ticket 43; admin API ticket 36): an operator finds the scenario's
 * transaction by txnId or by MSISDN, opens it and reads its saga timeline and audit trail. The
 * approval of a reversal by a second operator is in {@link AdminSteps}.
 */
public class OperatorSteps {

  private final ScenarioState state;
  private Http.Response search;
  private JsonNode detail;

  public OperatorSteps(ScenarioState state) {
    this.state = state;
  }

  @When("{word} searches the transactions by txnId")
  public void searchByTxnId(String user) {
    search = AdminApi.search(user, "txnId", state.txnId(), null);
  }

  /** By the initiator's number, in the form given (01..., 880..., +880...), one txnType. */
  @When("{word} searches the {word} transactions by MSISDN {word}")
  public void searchByMsisdn(String user, String txnType, String msisdn) {
    String txnId = state.txnId();
    // the orchestrator's query view is fed by the saga; the search is read right after the submit
    search =
        Waits.until(
            () -> AdminApi.search(user, "msisdn", msisdn, txnType),
            r -> r.status() == 200 && ids(r).contains(txnId),
            Duration.ofSeconds(10));
  }

  @Then("the search finds the transaction as {word} with the initiator masked as {word}")
  public void searchFinds(String status, String masked) {
    assertThat(search.status()).as("%s", search).isEqualTo(200);
    List<JsonNode> mine =
        search.body().path("items").valueStream()
            .filter(t -> state.txnId().equals(t.path("txnId").asString(null)))
            .toList();
    assertThat(mine).as("the scenario's transaction in %s", search).hasSize(1);
    JsonNode t = mine.getFirst();
    assertThat(t.path("status").asString(null)).as("%s", t).isEqualTo(status);
    assertThat(t.path("initiator").path("id").asString(null)).as("%s", t).isEqualTo(masked);
  }

  @Then("the search lists only the transaction")
  public void searchListsOnly() {
    assertThat(ids(search)).as("%s", search).containsExactly(state.txnId());
  }

  @When("{word} opens the transaction in the admin view")
  public void opens(String user) {
    Http.Response r = AdminApi.transaction(user, state.txnId());
    assertThat(r.status()).as("%s", r).isEqualTo(200);
    detail = r.body();
  }

  /** The {@code to} states of the timeline's STATE entries, in order (CORE entries in between). */
  @Then("the admin timeline shows the states {string}")
  public void timelineShows(String states) {
    List<String> seen =
        detail.path("timeline").valueStream()
            .filter(e -> "STATE".equals(e.path("kind").asString(null)))
            .map(e -> e.path("to").asString(null))
            .toList();
    assertThat(seen).as("timeline of %s", detail).containsSubsequence(split(states));
    assertThat(seen.getLast()).as("last state of %s", detail).isEqualTo(split(states).getLast());
  }

  @Then("the admin timeline shows the core command {word}")
  public void timelineShowsCommand(String command) {
    assertThat(
            detail.path("timeline").valueStream()
                .filter(e -> "CORE".equals(e.path("kind").asString(null)))
                .map(e -> e.path("command").asString(null)))
        .as("core commands in %s", detail.path("timeline"))
        .contains(command);
  }

  /** The {@code txn.state.changed} entries of the audit trail (mw-audit-dw), asynchronous. */
  @Then("the admin audit trail shows the states {string}")
  public void auditShows(String states) {
    List<String> expected = split(states);
    String txnId = state.txnId();
    String user = "ops-e2e-checker01";
    detail =
        Waits.until(
            () -> AdminApi.transaction(user, txnId).body(),
            d -> auditStates(d).size() >= expected.size(),
            Duration.ofSeconds(30));
    assertThat(auditStates(detail)).as("audit trail of %s", detail.path("auditTrail")).containsExactlyElementsOf(expected);
    assertThat(detail.path("auditTrail").valueStream().filter(e -> "txn.state.changed".equals(e.path("action").asString(null))))
        .allSatisfy(e -> assertThat(e.path("resource").asString(null)).isEqualTo("txn/" + txnId));
  }

  private static List<String> auditStates(JsonNode d) {
    return d.path("auditTrail").valueStream()
        .filter(e -> "txn.state.changed".equals(e.path("action").asString(null)))
        .map(e -> e.path("after").path("status").asString(null))
        .toList();
  }

  private static List<String> ids(Http.Response r) {
    return r.body().path("items").valueStream().map(t -> t.path("txnId").asString(null)).toList();
  }

  private static List<String> split(String states) {
    return Arrays.stream(states.split(",")).map(String::strip).toList();
  }
}
