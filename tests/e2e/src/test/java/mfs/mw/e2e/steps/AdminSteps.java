package mfs.mw.e2e.steps;

import static org.assertj.core.api.Assertions.assertThat;

import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import mfs.mw.e2e.support.AdminApi;
import mfs.mw.e2e.support.Http;
import mfs.mw.e2e.support.Waits;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Operator actions with maker-checker through the admin API (ticket 37, spec 15.3, acceptance
 * criterion 4): a maker asks for an action on the scenario's transaction, a second operator
 * approves or rejects it. Users of realm ops: ops-e2e-maker01 (ops-maker), ops-e2e-checker01
 * (ops-checker), dev-only, TOTP seeded.
 */
public class AdminSteps {

  private final ScenarioState state;
  private String changeRequestId;
  private Http.Response last;

  public AdminSteps(ScenarioState state) {
    this.state = state;
  }

  @When("{word} asks to {word} the transaction because {string}")
  public void ask(String user, String action, String reason) {
    last = AdminApi.ask(user, state.txnId(), action, reason);
    assertThat(last.status()).as("%s", last.body()).isEqualTo(201);
    changeRequestId = last.body().path("id").asString();
  }

  // --- USSD menus and SMS templates (ticket 39) -------------------------------------------------

  /** The shipped main menu (fixture copy) with the main screen's title in both languages. */
  @When("{word} proposes the main USSD menu for {word} with the title {string}")
  public void proposeMenu(String user, String mno, String title) throws IOException {
    ObjectNode tree;
    try (InputStream in = getClass().getClassLoader().getResourceAsStream("menus/main-e2e.json")) {
      tree = (ObjectNode) Http.JSON.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }
    tree.remove("menuId");
    tree.remove("version");
    String t = title.replace("{token}", state.token);
    ((ObjectNode) tree.path("nodes").path("main").path("text")).put("bn", t).put("en", t);
    ObjectNode body = Http.JSON.createObjectNode();
    body.set("menu", tree);
    body.putArray("activations").addObject().put("mno", mno);
    body.put("reason", "e2e title change " + state.token);
    last = AdminApi.proposeMenu(user, "main", body);
    assertThat(last.status()).as("%s", last.body()).isEqualTo(201);
    changeRequestId = last.body().path("id").asString();
  }

  @When("{word} asks to unlock wallet {word} because {string}")
  public void askUnlock(String user, String walletId, String reason) {
    last = AdminApi.askWallet(user, walletId, "UNLOCK", reason);
    assertThat(last.status()).as("%s", last.body()).isEqualTo(201);
    changeRequestId = last.body().path("id").asString();
  }

  @When("{word} proposes the {word} template {word} in {word} as {string}")
  public void proposeTemplate(String user, String channel, String code, String locale, String text) {
    ObjectNode body = Http.JSON.createObjectNode();
    body.put("body", text.replace("{token}", state.token)).put("senderId", "upay").put("reason", "e2e wording " + state.token);
    last = AdminApi.proposeTemplate(user, code, channel, locale, body);
    assertThat(last.status()).as("%s", last.body()).isEqualTo(201);
    changeRequestId = last.body().path("id").asString();
  }

  /** The approval reaches mw-ussd / mw-notify on mw.config.changed.v1 asynchronously. */
  @When("the services have had {int} seconds to take the published version")
  public void settle(int seconds) throws InterruptedException {
    Thread.sleep(Duration.ofSeconds(seconds));
  }

  @When("{word} approves the change request")
  public void approve(String user) {
    last = AdminApi.approve(user, changeRequestId);
  }

  @When("{word} rejects the change request because {string}")
  public void reject(String user, String reason) {
    last = AdminApi.reject(user, changeRequestId, reason);
  }

  @Then("the admin API answers {int} with code {string}")
  public void answers(int status, String code) {
    assertThat(last.status()).as("%s", last.body()).isEqualTo(status);
    assertThat(last.body().path("code").asString(null)).isEqualTo(code);
  }

  @Then("the change request is {word}")
  public void changeRequestIs(String status) {
    JsonNode cr = AdminApi.changeRequest("ops-e2e-checker01", changeRequestId).body();
    assertThat(cr.path("status").asString(null)).as("%s", cr).isEqualTo(status);
  }

  @Then("the admin view shows the transaction as {word} within {int} seconds")
  public void adminViewShows(String status, int seconds) {
    JsonNode detail =
        Waits.until(
            () -> AdminApi.transaction("ops-e2e-checker01", state.txnId()).body(),
            d -> status.equals(d.path("transaction").path("status").asString(null)),
            Duration.ofSeconds(seconds));
    assertThat(detail.path("transaction").path("status").asString(null)).as("%s", detail).isEqualTo(status);
  }

  /** The decision is in the transaction's audit trail (mw-audit-dw), with before and after. */
  @Then("the admin audit trail of the transaction records the {word} change request")
  public void auditRecords(String decision) {
    String action = "admin.change_request." + decision.toLowerCase();
    JsonNode detail =
        Waits.until(
            () -> AdminApi.transaction("ops-e2e-checker01", state.txnId()).body(),
            d -> entries(d, action).size() == 1,
            Duration.ofSeconds(30));
    List<JsonNode> found = entries(detail, action);
    assertThat(found).as("%s in %s", action, detail.path("auditTrail")).hasSize(1);
    JsonNode e = found.getFirst();
    assertThat(e.path("before").path("status").asString(null)).isEqualTo("PENDING");
    assertThat(e.path("after").path("status").asString(null)).isEqualTo(decision);
    assertThat(e.path("after").path("changeRequestId").asString(null)).isEqualTo(changeRequestId);
  }

  private static List<JsonNode> entries(JsonNode detail, String action) {
    return detail.path("auditTrail").valueStream().filter(x -> action.equals(x.path("action").asString(null))).toList();
  }
}
