package mfs.mw.e2e.steps;

import static org.assertj.core.api.Assertions.assertThat;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import mfs.mw.e2e.support.Http;
import mfs.mw.e2e.support.PortalApi;
import mfs.mw.e2e.support.Waits;
import tools.jackson.databind.JsonNode;

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

  // --- execution and result file (ticket 34) --------------------------------------------------

  private static final Set<String> SENDING = Set.of("APPROVED", "EXECUTING");

  /** Waits until no batch of the user's org is still sending rows (a paused one sends nothing). */
  @Given("no bulk batch of {word}'s org is still running")
  public void noBatchRunning(String user) {
    Http.Response r =
        Waits.until(
            () -> PortalApi.list(user),
            resp -> {
              for (JsonNode b : resp.body().path("batches")) {
                if (SENDING.contains(b.path("status").asString())) {
                  return false;
                }
              }
              return resp.status() == 200;
            },
            Duration.ofMinutes(15));
    assertThat(r.status()).as("%s", r).isEqualTo(200);
  }

  @Given("{word} has uploaded a payout file of {int} rows of {word} taka")
  public void hasUploadedPayout(String user, int rows, String taka) {
    Http.Response r = record(PortalApi.upload(user, PortalApi.payoutFile(rows, taka)));
    assertThat(r.status()).as("%s", r).isEqualTo(201);
    state.batchId = r.body().path("batchId").asString();
  }

  @Given("{word} has uploaded a bulk file with these lines:")
  public void hasUploadedLines(String user, List<String> lines) {
    Http.Response r = record(PortalApi.upload(user, String.join("\n", lines) + "\n"));
    assertThat(r.status()).as("%s", r).isEqualTo(201);
    state.batchId = r.body().path("batchId").asString();
  }

  @When("{word} pauses the batch")
  public void pauses(String user) {
    record(PortalApi.pause(user, state.batchId));
  }

  @When("{word} resumes the batch")
  public void resumes(String user) {
    record(PortalApi.resume(user, state.batchId));
  }

  @Then("within {int} minutes {word} sees the batch {word}")
  public void eventuallyStatus(int minutes, String user, String status) {
    Http.Response r =
        Waits.until(
            () -> PortalApi.get(user, state.batchId),
            resp -> status.equals(resp.body().path("status").asString(null)),
            Duration.ofMinutes(minutes));
    record(r);
    assertThat(r.body().path("status").asString(null)).as("%s", r).isEqualTo(status);
  }

  @Then("the batch progress shows {int} completed, {int} failed and {long} poisha paid")
  public void progress(int completed, int failed, long paidMinor) {
    JsonNode p = state.response().body().path("progress");
    assertThat(p.path("completed").asInt()).as("%s", state.response()).isEqualTo(completed);
    assertThat(p.path("failed").asInt()).as("%s", state.response()).isEqualTo(failed);
    assertThat(p.path("pending").asInt()).as("%s", state.response()).isZero();
    assertThat(p.path("inFlight").asInt()).as("%s", state.response()).isZero();
    assertThat(p.path("paidAmount").path("valueMinor").asLong()).as("%s", state.response()).isEqualTo(paidMinor);
  }

  /** The batch sends nothing more for 3 s, with rows still unsent. */
  @Then("{word} sees the batch send no more rows")
  public void sendsNoMore(String user) {
    int before = PortalApi.get(user, state.batchId).body().path("progress").path("pending").asInt();
    assertThat(before).as("rows still unsent when paused").isPositive();
    Waits.until(() -> 0, x -> false, Duration.ofSeconds(3));
    int after = PortalApi.get(user, state.batchId).body().path("progress").path("pending").asInt();
    assertThat(after).isEqualTo(before);
  }

  @Then("{word}'s result file lists all {int} rows as COMPLETED, each with its own txnId")
  public void resultAllCompleted(String user, int rows) {
    List<String[]> lines = resultLines(user);
    assertThat(lines).hasSize(rows);
    Set<String> txnIds = new HashSet<>();
    for (String[] l : lines) {
      assertThat(l[4]).as("row %s", l[0]).isEqualTo("COMPLETED");
      assertThat(l[1]).as("masked msisdn").matches("01\\d{2}\\*{4}\\d{3}");
      txnIds.add(l[5]);
    }
    assertThat(txnIds).hasSize(rows);
  }

  @Then("{word}'s result file shows:")
  public void resultShows(String user, List<Map<String, String>> expected) {
    List<Map<String, String>> actual = new ArrayList<>();
    for (String[] l : resultLines(user)) {
      actual.add(Map.of("reference", l[3], "status", l[4], "errorCode", l.length > 6 ? l[6] : ""));
    }
    List<Map<String, String>> wanted = new ArrayList<>();
    for (Map<String, String> e : expected) {
      wanted.add(Map.of("reference", e.get("reference"), "status", e.get("status"),
          "errorCode", e.get("errorCode") == null ? "" : e.get("errorCode")));
    }
    assertThat(actual).containsExactlyElementsOf(wanted);
  }

  /** The data lines of the result file, split into columns (header checked). */
  private List<String[]> resultLines(String user) {
    Http.Response r = record(PortalApi.result(user, state.batchId));
    assertThat(r.status()).as("%s", r).isEqualTo(200);
    List<String> lines = r.raw().lines().toList();
    assertThat(lines.get(0)).isEqualTo("line,msisdn,amount,reference,status,txnId,errorCode");
    return lines.subList(1, lines.size()).stream().map(l -> l.split(",", -1)).toList();
  }

  // --- SP transaction query (ticket 35) -----------------------------------------------------

  private String firstListedTxnId;

  @When("{word} lists the org's transactions of the batch")
  public void listsBatchTransactions(String user) {
    Http.Response r = record(PortalApi.transactions(user, state.batchId));
    if (r.status() == 200 && !r.body().path("items").isEmpty()) {
      firstListedTxnId = r.body().path("items").get(0).path("txnId").asString();
    }
  }

  @When("{word} lists the org's transactions")
  public void listsTransactions(String user) {
    record(PortalApi.transactions(user, null));
  }

  @Then("the transaction list has {int} {word} {word} transaction paying {word}")
  public void transactionList(int count, String status, String txnType, String maskedMsisdn) {
    Http.Response r = state.response();
    JsonNode items = r.body().path("items");
    assertThat(items.size()).as("%s", r).isEqualTo(count);
    for (JsonNode t : items) {
      assertThat(t.path("status").asString()).as("%s", r).isEqualTo(status);
      assertThat(t.path("txnType").asString()).as("%s", r).isEqualTo(txnType);
      assertThat(t.path("creditParty").path("id").asString()).as("%s", r).isEqualTo(maskedMsisdn);
      assertThat(t.path("attributes").path("batchId").asString(null)).as("%s", r).isEqualTo(state.batchId);
    }
  }

  @Then("every listed transaction is tagged {word}")
  public void everyListedTagged(String role) {
    Http.Response r = state.response();
    JsonNode items = r.body().path("items");
    assertThat(items.isEmpty()).as("%s", r).isFalse();
    for (JsonNode t : items) {
      assertThat(t.path("role").asString()).as("%s", r).isEqualTo(role);
    }
  }

  /** The scenario's own transaction (e.g. a bill payment) in the last org list, if listed. */
  private JsonNode listed() {
    String txnId = state.txnId();
    for (JsonNode t : state.response().body().path("items")) {
      if (txnId.equals(t.path("txnId").asString())) {
        return t;
      }
    }
    return null;
  }

  @Then("the org's transaction list has the transaction tagged {word}")
  public void listHasTransaction(String role) {
    JsonNode t = listed();
    assertThat(t).as("transaction %s in %s", state.txnId(), state.response()).isNotNull();
    assertThat(t.path("role").asString()).as("%s", t).isEqualTo(role);
  }

  @Then("the org's transaction list does not have the transaction")
  public void listLacksTransaction() {
    assertThat(listed()).as("transaction %s in %s", state.txnId(), state.response()).isNull();
  }

  @When("{word} opens the transaction")
  public void opensScenarioTransaction(String user) {
    record(PortalApi.transaction(user, state.txnId()));
  }

  @Then("the opened transaction is tagged {word}")
  public void openedTagged(String role) {
    assertThat(state.response().body().path("transaction").path("role").asString())
        .as("%s", state.response())
        .isEqualTo(role);
  }

  @When("{word} opens the first listed transaction")
  public void opensFirst(String user) {
    assertThat(firstListedTxnId).as("a transaction was listed before").isNotNull();
    record(PortalApi.transaction(user, firstListedTxnId));
  }

  @Then("the timeline ends {string}")
  public void timelineEnds(String chain) {
    JsonNode steps = state.response().body().path("timeline");
    List<String> states = new ArrayList<>();
    for (JsonNode s : steps) {
      if (states.isEmpty()) {
        states.add(s.path("from").asString());
      }
      states.add(s.path("to").asString());
    }
    assertThat(String.join(" -> ", states)).as("%s", state.response()).endsWith(chain);
  }

  @Then("the error details name these fields:")
  public void detailFields(List<String> fields) {
    List<String> actual = new ArrayList<>();
    state.response().body().path("details").forEach(d -> actual.add(d.path("field").asString()));
    assertThat(actual).as("%s", state.response()).containsExactlyElementsOf(fields);
  }
}
