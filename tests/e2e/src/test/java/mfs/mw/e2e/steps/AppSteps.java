package mfs.mw.e2e.steps;

import static org.assertj.core.api.Assertions.assertThat;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.time.Duration;
import mfs.mw.e2e.support.AppApi;
import mfs.mw.e2e.support.Http;
import mfs.mw.e2e.support.Keycloak;
import tools.jackson.databind.node.ObjectNode;

/** Steps of the app channel: sign in, submit a transaction, read the answer and the status view. */
public class AppSteps {

  private final ScenarioState state;

  public AppSteps(ScenarioState state) {
    this.state = state;
  }

  @Given("{word} is signed in to the app")
  public void signedIn(String user) {
    Keycloak.customerToken(user); // fails early when Keycloak or the token route is broken
    state.user = user;
  }

  @When("{word} sends {long} poisha P2P to {word} with PIN {string}")
  public void sendsP2p(String user, long amountMinor, String credit, String pin) {
    submit(user, AppApi.newIdempotencyKey(), AppApi.request("P2P", credit, amountMinor, pin));
  }

  @When("{word} sends {long} poisha P2P to {word} with PIN {string} reusing the idempotency key")
  public void sendsP2pReusingKey(String user, long amountMinor, String credit, String pin) {
    submit(user, state.idempotencyKey, AppApi.request("P2P", credit, amountMinor, pin));
  }

  /**
   * Any txnType whose body is the plain request (P2P, CASH_IN, CASH_OUT): the counterparty is the
   * body's {@code creditParty} wallet (the payee, or for CASH_OUT the paying customer).
   */
  @When("{word} submits {word} of {long} poisha with counterparty {word} and PIN {string}")
  public void submits(String user, String txnType, long amountMinor, String counterparty, String pin) {
    submit(user, AppApi.newIdempotencyKey(), AppApi.request(txnType, counterparty, amountMinor, pin));
  }

  @When("{word} sends the same request again with the same idempotency key")
  public void repeats(String user) {
    submit(user, state.idempotencyKey, state.lastRequest);
  }

  @Then("the response status is {int}")
  public void responseStatus(int status) {
    assertThat(state.response().status()).as("%s", state.response()).isEqualTo(status);
  }

  @Then("the transaction status is {word}")
  public void transactionStatus(String status) {
    assertThat(state.response().body().path("status").asString(null)).as("%s", state.response()).isEqualTo(status);
  }

  @Then("the error code is {string}")
  public void errorCode(String code) {
    assertThat(state.response().body().path("code").asString(null)).as("%s", state.response()).isEqualTo(code);
  }

  @Then("the response carries a txnId")
  public void carriesTxnId() {
    assertThat(state.response().body().path("txnId").asString(null)).as("%s", state.response()).isNotBlank();
  }

  @Then("every response carries the same txnId")
  public void sameTxnId() {
    assertThat(state.responses)
        .extracting(r -> r.body().path("txnId").asString(null))
        .as("%s", state.responses)
        .doesNotContainNull()
        .containsOnly(state.txnId());
  }

  @Then("the app shows the transaction as {word}")
  public void appShows(String status) {
    Http.Response r =
        AppApi.pollStatus(
            state.user, state.txnId(), x -> status.equals(x.body().path("status").asString(null)), Duration.ofSeconds(20));
    assertThat(r.status()).as("%s", r).isEqualTo(200);
    assertThat(r.body().path("status").asString(null)).as("%s", r).isEqualTo(status);
  }

  private void submit(String user, String idempotencyKey, ObjectNode body) {
    state.user = user;
    state.idempotencyKey = idempotencyKey;
    state.lastRequest = body;
    state.responses.add(AppApi.submit(user, idempotencyKey, body));
  }
}
