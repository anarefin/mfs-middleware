package mfs.mw.e2e.steps;

import static org.assertj.core.api.Assertions.assertThat;

import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.time.Duration;
import java.util.List;
import mfs.mw.e2e.support.AppApi;
import mfs.mw.e2e.support.Http;
import mfs.mw.e2e.support.Waits;
import tools.jackson.databind.JsonNode;

/**
 * Merchant app steps (ticket 30): the collections view {@code GET /app/v1/merchant/collections}.
 * The transaction is the scenario's first one (the customer's MERCHANT_PAY); the collections are
 * filled from {@code mw.txn.status.v1}, so the positive check polls.
 */
public class MerchantSteps {

  private final ScenarioState state;

  public MerchantSteps(ScenarioState state) {
    this.state = state;
  }

  @When("{word} lists its collections")
  public void listsCollections(String user) {
    state.responses.add(AppApi.merchantCollections(user));
  }

  @Then("{word} sees the transaction in its collections with the payer masked as {word}")
  public void seesTheTransaction(String merchant, String maskedPayer) {
    String txnId = state.txnId();
    Http.Response r =
        Waits.until(
            () -> AppApi.merchantCollections(merchant), x -> entry(x, txnId) != null, Duration.ofSeconds(20));
    assertThat(r.status()).as("%s", r).isEqualTo(200);
    JsonNode c = entry(r, txnId);
    assertThat(c).as("collections of %s: %s", merchant, r).isNotNull();
    assertThat(c.path("status").asString(null)).isEqualTo("COMPLETED");
    assertThat(c.path("payer").asString(null)).isEqualTo(maskedPayer);
    assertThat(c.path("amount").path("valueMinor").asLong())
        .isEqualTo(state.lastRequest.path("amount").path("valueMinor").asLong());
  }

  @Then("{word} does not see the transaction in its collections")
  public void doesNotSeeTheTransaction(String merchant) {
    Http.Response r = AppApi.merchantCollections(merchant);
    assertThat(r.status()).as("%s", r).isEqualTo(200);
    assertThat(entry(r, state.txnId())).as("collections of %s: %s", merchant, r).isNull();
  }

  private static JsonNode entry(Http.Response r, String txnId) {
    if (r.status() != 200) {
      return null;
    }
    List<JsonNode> hits = r.body().path("collections").valueStream()
        .filter(c -> txnId.equals(c.path("txnId").asString(null)))
        .toList();
    return hits.isEmpty() ? null : hits.getFirst();
  }
}
