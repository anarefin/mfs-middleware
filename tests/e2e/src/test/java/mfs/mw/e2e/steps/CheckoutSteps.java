package mfs.mw.e2e.steps;

import static org.assertj.core.api.Assertions.assertThat;

import io.cucumber.java.After;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import mfs.mw.e2e.support.CheckoutApi;
import mfs.mw.e2e.support.CoreMock;
import mfs.mw.e2e.support.Http;
import mfs.mw.e2e.support.MerchantIpn;
import mfs.mw.e2e.support.Smsc;
import mfs.mw.e2e.support.Stack;
import mfs.mw.e2e.support.Waits;

/**
 * Web checkout steps (ticket 31): the dev merchant {@code merchant_demo} (wallet 01911000001)
 * creates a session through the edge (hmac-auth), the customer pays on the hosted page API with
 * the OTP it receives by SMS (read at the SMSC simulator) and its PIN, and the merchant verifies
 * the signed result it is redirected to. Responses go to {@link ScenarioState#responses}, so the
 * generic "the response status is" / "the error code is" steps apply.
 */
public class CheckoutSteps {

  private static final Pattern OTP = Pattern.compile("Your upay code is ([0-9]{6}) to pay Tk ([0-9.]+) to Demo Shop");

  private final ScenarioState state;
  private String orderId;
  private long amountMinor;
  private String sessionId;
  private String token;
  private String otp;

  public CheckoutSteps(ScenarioState state) {
    this.state = state;
  }

  @Given("the merchant creates a checkout session for {long} poisha")
  public void createsSession(long amount) {
    orderId = "E2E-" + UUID.randomUUID();
    amountMinor = amount;
    Http.Response r = CheckoutApi.createSession(CheckoutApi.sessionBody(orderId, amount), CheckoutApi.secret());
    state.responses.add(r);
    assertThat(r.status()).as("%s", r).isEqualTo(201);
    sessionId = r.body().path("sessionId").asString();
    token = CheckoutApi.token(r.body().path("checkoutUrl").asString());
    assertThat(r.body().path("status").asString()).isEqualTo("OPEN");
  }

  @When("the merchant creates a checkout session signed with a wrong secret")
  public void badlySigned() {
    state.responses.add(CheckoutApi.createSession(
        CheckoutApi.sessionBody("E2E-" + UUID.randomUUID(), 1000), "not-the-merchant-secret"));
  }

  @When("the merchant creates a checkout session without a signature")
  public void unsigned() {
    state.responses.add(CheckoutApi.createSessionUnsigned(CheckoutApi.sessionBody("E2E-" + UUID.randomUUID(), 1000)));
  }

  @Then("the checkout page shows {long} poisha for Demo Shop with the core's PIN key")
  public void pageShows(long amount) {
    Http.Response r = CheckoutApi.page(token);
    assertThat(r.status()).as("%s", r).isEqualTo(200);
    assertThat(r.body().path("merchantName").asString()).isEqualTo("Demo Shop");
    assertThat(r.body().path("amount").path("valueMinor").asLong()).isEqualTo(amount);
    assertThat(r.body().path("pinKey").path("keyId").asString()).isEqualTo(CoreMock.pinKey().keyId());
  }

  @When("the customer asks for an OTP for wallet {word}")
  public void asksForOtp(String wallet) {
    List<String> before = otpsTo(wallet);
    Http.Response r = CheckoutApi.requestOtp(token, wallet);
    state.responses.add(r);
    assertThat(r.status()).as("%s", r).isEqualTo(202);
    // the SMS goes out asynchronously: outbox -> mw.notify.request.v1 -> mw-notify -> SMPP
    List<String> fresh = Waits.until(
        () -> otpsTo(wallet).stream().filter(c -> !before.contains(c)).toList(), l -> !l.isEmpty(), Duration.ofSeconds(30));
    assertThat(fresh).as("a new OTP SMS to %s", wallet).hasSize(1);
    otp = fresh.getFirst();
  }

  @Then("the OTP was sent with OTP priority")
  public void otpPriority() {
    String priority = Stack.get().psql(
        "select priority from notify.message where message_id like 'checkout-otp:" + sessionId + ":%'"
            + " order by message_id desc limit 1");
    assertThat(priority).isEqualTo("OTP");
  }

  @When("the customer confirms with a wrong OTP and PIN {string}")
  public void wrongOtp(String pin) {
    state.responses.add(CheckoutApi.confirm(token, otp.equals("000000") ? "999999" : "000000", pin));
  }

  @When("the customer confirms with the OTP and PIN {string}")
  public void confirms(String pin) {
    state.responses.add(CheckoutApi.confirm(token, otp, pin));
  }

  @Then("the checkout result is {word}")
  public void resultIs(String status) {
    assertThat(state.response().body().path("status").asString()).as("%s", state.response()).isEqualTo(status);
  }

  @Then("the browser is redirected to the merchant's {word} URL with a {word} result the merchant verifies")
  public void redirected(String which, String status) {
    Http.Response r = CheckoutApi.back(token);
    assertThat(r.status()).as("%s", r).isEqualTo(303);
    String location = r.header("Location");
    assertThat(location).startsWith("https://shop.example.test/checkout/" + which);
    Map<String, String> q = CheckoutApi.query(location);
    assertThat(q).containsEntry("status", status).containsEntry("orderId", orderId)
        .containsEntry("sessionId", sessionId).containsEntry("amount", Long.toString(amountMinor));
    assertThat(CheckoutApi.verifyResult(q, CheckoutApi.secret())).as("signature of %s", location).isTrue();
    Map<String, String> tampered = new java.util.LinkedHashMap<>(q);
    tampered.put("status", "PAID".equals(status) ? "FAILED" : "PAID");
    assertThat(CheckoutApi.verifyResult(tampered, CheckoutApi.secret())).as("a tampered result").isFalse();
    if ("PAID".equals(status)) {
      assertThat(q).containsEntry("cart", "7").containsKey("txnId");
      String confirmedRedirect = state.response().body().path("redirectUrl").asString(null);
      if (confirmedRedirect != null) {
        assertThat(CheckoutApi.verifyResult(CheckoutApi.query(confirmedRedirect), CheckoutApi.secret())).isTrue();
      }
    }
  }

  @Then("the merchant sees the session as {word} paid by {word}")
  public void merchantSees(String status, String maskedPayer) {
    Http.Response r = CheckoutApi.getSession(sessionId);
    assertThat(r.status()).as("%s", r).isEqualTo(200);
    assertThat(r.body().path("status").asString()).isEqualTo(status);
    assertThat(r.body().path("payer").asString()).isEqualTo(maskedPayer);
    assertThat(r.body().path("txnId").asString(null)).isNotNull();
  }

  /** For an outcome that resolves after the confirm answered 202 (status inquiry, spec 7.4). */
  @Then("the merchant sees the session as {word} within {int} seconds")
  public void merchantSeesStatusWithin(String status, int seconds) {
    Http.Response r =
        Waits.until(
            () -> CheckoutApi.getSession(sessionId),
            x -> status.equals(x.body().path("status").asString(null)),
            Duration.ofSeconds(seconds));
    assertThat(r.body().path("status").asString(null)).as("%s", r).isEqualTo(status);
  }

  @Then("the merchant sees the session as {word}")
  public void merchantSeesStatus(String status) {
    Http.Response r = CheckoutApi.getSession(sessionId);
    assertThat(r.status()).as("%s", r).isEqualTo(200);
    assertThat(r.body().path("status").asString()).isEqualTo(status);
  }

  // ---- signed IPN (ticket 32) -----------------------------------------------------------------

  @After("@ipn")
  public void merchantAcceptsAgain() {
    MerchantIpn.acceptAll();
  }

  @Given("the merchant's IPN endpoint fails the next {int} IPNs of the session with status {int}")
  public void ipnFails(int count, int status) {
    MerchantIpn.failNext(sessionId, count, status);
  }

  @Then("within {int} seconds the merchant has received the session's IPN {int} times, answering {string}")
  public void ipnReceived(int seconds, int times, String answers) {
    List<MerchantIpn.Received> got =
        Waits.until(() -> MerchantIpn.received(sessionId), l -> l.size() >= times, Duration.ofSeconds(seconds));
    assertThat(got).as("IPNs of session %s", sessionId).hasSize(times);
    List<Integer> expected =
        java.util.Arrays.stream(answers.split(",")).map(String::trim).map(Integer::valueOf).toList();
    assertThat(got).extracting(MerchantIpn.Received::answeredStatus).containsExactlyElementsOf(expected);
    assertThat(got).extracting(MerchantIpn.Received::attempt)
        .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, times).boxed().toList());
  }

  @Then("the merchant accepted exactly one IPN of the session, a {word} result with a valid signature")
  public void ipnDeliveredOnce(String status) {
    List<MerchantIpn.Received> got = MerchantIpn.received(sessionId);
    List<MerchantIpn.Received> accepted = got.stream().filter(r -> r.answeredStatus() / 100 == 2).toList();
    assertThat(accepted).as("accepted IPNs of session %s", sessionId).hasSize(1);
    MerchantIpn.Received ipn = accepted.getFirst();
    assertThat(MerchantIpn.verify(ipn, CheckoutApi.secret())).as("IPN signature").isTrue();
    MerchantIpn.Received tampered =
        new MerchantIpn.Received(ipn.attempt(), ipn.timestamp(), ipn.signature(), ipn.body().replace(status, "X"), 200);
    assertThat(MerchantIpn.verify(tampered, CheckoutApi.secret())).as("a tampered IPN").isFalse();
    assertThat(got).allSatisfy(r -> assertThat(MerchantIpn.verify(r, CheckoutApi.secret())).isTrue());
    assertThat(got).extracting(MerchantIpn.Received::body).as("the same body on every attempt").containsOnly(ipn.body());
    var body = Http.JSON.readTree(ipn.body());
    assertThat(body.path("sessionId").asString()).isEqualTo(sessionId);
    assertThat(body.path("orderId").asString()).isEqualTo(orderId);
    assertThat(body.path("status").asString()).isEqualTo(status);
    assertThat(body.path("amount").path("valueMinor").asLong()).isEqualTo(amountMinor);
    assertThat(body.path("txnId").asString(null)).isNotNull();
  }

  @Then("the merchant receives no further IPN of the session within {int} seconds")
  public void noFurtherIpn(int seconds) {
    int before = MerchantIpn.received(sessionId).size();
    List<MerchantIpn.Received> later =
        Waits.until(() -> MerchantIpn.received(sessionId), l -> l.size() > before, Duration.ofSeconds(seconds));
    assertThat(later).hasSize(before);
  }

  /** The OTP codes of this session's amount that {@code wallet} received, oldest first. */
  private List<String> otpsTo(String wallet) {
    String taka = String.format(java.util.Locale.ROOT, "%d.%02d", amountMinor / 100, amountMinor % 100);
    return Smsc.messagesTo(wallet).stream()
        .map(m -> OTP.matcher(m.text()))
        .filter(Matcher::find)
        .filter(m -> m.group(2).equals(taka))
        .map(m -> m.group(1))
        .toList();
  }
}
