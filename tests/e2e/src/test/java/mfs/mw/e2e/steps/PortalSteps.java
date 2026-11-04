package mfs.mw.e2e.steps;

import static org.assertj.core.api.Assertions.assertThat;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.util.ArrayList;
import java.util.List;
import mfs.mw.e2e.support.Http;
import mfs.mw.e2e.support.PortalApi;

/**
 * Org portal bulk upload and maker-checker (ticket 33). Every response goes to {@link
 * ScenarioState#responses}, so "the response status is N" and "the error code is C" apply.
 */
public class PortalSteps {

  private final ScenarioState state;

  public PortalSteps(ScenarioState state) {
    this.state = state;
  }

  private Http.Response record(Http.Response r) {
    state.responses.add(r);
    return r;
  }

  @When("{word} uploads a bulk file of {int} rows")
  public void uploads(String user, int rows) {
    Http.Response r = record(PortalApi.upload(user, PortalApi.file(rows)));
    if (r.status() == 201) {
      state.batchId = r.body().path("batchId").asString();
    }
  }

  @Given("{word} has uploaded a bulk file of {int} rows")
  public void hasUploaded(String user, int rows) {
    uploads(user, rows);
    assertThat(state.response().status()).as("%s", state.response()).isEqualTo(201);
  }

  @When("{word} uploads a bulk file with these lines:")
  public void uploadsLines(String user, List<String> lines) {
    record(PortalApi.upload(user, String.join("\n", lines) + "\n"));
  }

  @When("{word} approves the batch")
  public void approves(String user) {
    record(PortalApi.approve(user, state.batchId));
  }

  @When("{word} rejects the batch because {string}")
  public void rejects(String user, String reason) {
    record(PortalApi.reject(user, state.batchId, reason));
  }

  @When("{word} looks at the batch")
  public void looksAt(String user) {
    record(PortalApi.get(user, state.batchId));
  }

  @Then("the batch status is {word}")
  public void batchStatus(String status) {
    assertThat(state.response().body().path("status").asString(null)).as("%s", state.response()).isEqualTo(status);
  }

  @Then("the batch has {int} rows totalling {long} poisha")
  public void batchTotals(int rows, long totalMinor) {
    Http.Response r = state.response();
    assertThat(r.body().path("rowCount").asInt()).as("%s", r).isEqualTo(rows);
    assertThat(r.body().path("totalAmount").path("valueMinor").asLong()).as("%s", r).isEqualTo(totalMinor);
  }

  @Then("the batch was decided by {word}")
  public void decidedBy(String user) {
    assertThat(state.response().body().path("checkerId").asString(null)).as("%s", state.response()).isEqualTo(user);
  }

  @Then("the error details name these fields:")
  public void detailFields(List<String> fields) {
    List<String> actual = new ArrayList<>();
    state.response().body().path("details").forEach(d -> actual.add(d.path("field").asString()));
    assertThat(actual).as("%s", state.response()).containsExactlyElementsOf(fields);
  }
}
