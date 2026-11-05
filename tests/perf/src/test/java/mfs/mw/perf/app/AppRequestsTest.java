package mfs.mw.perf.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.MGF1ParameterSpec;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.random.RandomGenerator;
import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class AppRequestsTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final KeyPair CORE_KEY = rsa();
  private static final PinCipher PIN = new PinCipher("core-pin-test", CORE_KEY.getPublic());
  private final AppRequests requests = new AppRequests(PIN, "1234");

  @Test
  void a_p2p_names_the_counterparty_wallet_and_carries_the_pin_encrypted_for_the_core() throws Exception {
    JsonNode b = JSON.readTree(requests.p2p("01711000102"));

    assertThat(b.path("txnType").asString()).isEqualTo("P2P");
    assertThat(b.path("creditParty").path("id").asString()).isEqualTo("01711000102");
    assertThat(b.path("amount").path("valueMinor").asLong()).isEqualTo(1000);
    assertThat(b.path("amount").path("currency").asString()).isEqualTo("BDT");
    assertThat(b.path("encryptedPin").path("keyId").asString()).isEqualTo("core-pin-test");
    assertThat(decrypt(b.path("encryptedPin").path("ciphertext").asString())).isEqualTo("1234");
  }

  @Test
  void cash_in_cash_out_and_merchant_pay_are_plain_requests_of_their_type() {
    assertThat(JSON.readTree(requests.cashIn("01711000101")).path("txnType").asString()).isEqualTo("CASH_IN");
    assertThat(JSON.readTree(requests.cashOut("01811000011")).path("txnType").asString()).isEqualTo("CASH_OUT");
    JsonNode m = JSON.readTree(requests.merchantPay("01911000003"));
    assertThat(m.path("txnType").asString()).isEqualTo("MERCHANT_PAY");
    assertThat(m.path("creditParty").path("id").asString()).isEqualTo("01911000003");
  }

  @Test
  void a_top_up_recharges_an_msisdn_at_gp() {
    JsonNode b = JSON.readTree(requests.topUp("01711000101"));

    assertThat(b.path("txnType").asString()).isEqualTo("TOPUP");
    assertThat(b.path("creditParty").path("type").asString()).isEqualTo("MSISDN");
    assertThat(b.path("creditParty").path("id").asString()).isEqualTo("01711000101");
    assertThat(b.path("partner").path("partnerId").asString()).isEqualTo("gp");
    assertThat(b.path("partner").path("productCode").asString()).isEqualTo("PREPAID");
  }

  @Test
  void the_refused_top_up_goes_to_a_number_the_ocs_simulator_refuses() {
    JsonNode b = JSON.readTree(requests.topUp(AppRequests.REFUSED_TOPUP_MSISDN));

    assertThat(b.path("creditParty").path("id").asString()).endsWith("999").matches("01[3-9][0-9]{8}");
  }

  @Test
  void a_bill_payment_pays_the_amount_due_of_a_desco_bill() {
    JsonNode b = JSON.readTree(requests.billPay("4567321", 42100));

    assertThat(b.path("txnType").asString()).isEqualTo("BILL_PAY");
    assertThat(b.path("creditParty").path("type").asString()).isEqualTo("BILLER_ACCOUNT");
    assertThat(b.path("creditParty").path("id").asString()).isEqualTo("4567321");
    assertThat(b.path("partner").path("billerCode").asString()).isEqualTo("DESCO");
    assertThat(b.path("amount").path("valueMinor").asLong()).isEqualTo(42100);
  }

  @Test
  void bill_numbers_avoid_every_magic_suffix_and_their_due_follows_the_simulator_rule() {
    RandomGenerator r = RandomGenerator.of("L64X128MixRandom");
    for (int i = 0; i < 5000; i++) {
      String bill = AppRequests.billNumber(r);
      assertThat(bill).matches("[1-9][0-9]{6}").doesNotEndWith("000").doesNotEndWith("404").doesNotEndWith("999");
      // cheap bills (101..150 taka) so customers' balances and daily limits last a whole run
      assertThat(AppRequests.dueMinor(bill)).isBetween(10100L, 15000L);
    }
    // README of tests/e2e: 100 taka + the last three digits in taka (1234567 -> 66700 poisha)
    assertThat(AppRequests.dueMinor("1234567")).isEqualTo(66700);
    assertThat(AppRequests.dueMinor("4567001")).isEqualTo(10100);
  }

  @Test
  void add_money_debits_a_linked_account_at_c2wbank_and_names_no_credit_party() {
    JsonNode b = JSON.readTree(requests.addMoneyBank("1234567890"));

    assertThat(b.path("txnType").asString()).isEqualTo("ADD_MONEY_BANK");
    assertThat(b.has("creditParty")).isFalse();
    assertThat(b.path("debitParty").path("type").asString()).isEqualTo("BANK_ACCOUNT");
    assertThat(b.path("debitParty").path("id").asString()).isEqualTo("1234567890");
    assertThat(b.path("partner").path("partnerId").asString()).isEqualTo("c2wbank");
    // more than the run takes out of a customer on average, so balances last across runs
    assertThat(b.path("amount").path("valueMinor").asLong()).isEqualTo(50000);
  }

  @Test
  void amounts_never_hit_a_simulator_magic_fraction() {
    // .13 timeout, .55 malformed, .77 5xx, .99 ambiguous: whole taka only
    for (String body : new String[] {requests.p2p("01711000102"), requests.cashIn("01711000101"),
        requests.cashOut("01811000011"), requests.merchantPay("01911000003"), requests.topUp("01711000101"),
        requests.addMoneyBank("1234567890")}) {
      assertThat(JSON.readTree(body).path("amount").path("valueMinor").asLong() % 100).isZero();
    }
  }

  @Test
  void a_signed_submit_carries_the_four_signature_headers_over_the_exact_body() throws Exception {
    Device device = Device.create();
    String body = requests.p2p("01711000102");

    Map<String, String> h = device.signedHeaders("token-1", "POST", "/app/v1/transactions", body);

    assertThat(h).containsEntry("Authorization", "Bearer token-1").containsEntry("X-Device-Id", device.id());
    String canonical = String.join("\n", "POST", "/app/v1/transactions", sha256Hex(body),
        h.get("X-Timestamp"), h.get("X-Nonce"), device.id());
    Signature s = Signature.getInstance("SHA256withECDSA");
    s.initVerify(device.publicKey());
    s.update(canonical.getBytes(StandardCharsets.UTF_8));
    assertThat(s.verify(Base64.getDecoder().decode(h.get("X-Signature")))).isTrue();
    assertThat(device.signedHeaders("token-1", "POST", "/app/v1/transactions", body).get("X-Nonce"))
        .isNotEqualTo(h.get("X-Nonce"));
  }

  private static String decrypt(String b64) throws Exception {
    Cipher c = Cipher.getInstance("RSA/ECB/OAEPPadding");
    c.init(Cipher.DECRYPT_MODE, CORE_KEY.getPrivate(),
        new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
    return new String(c.doFinal(Base64.getDecoder().decode(b64)), StandardCharsets.UTF_8);
  }

  private static String sha256Hex(String s) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
  }

  private static KeyPair rsa() {
    try {
      KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
      g.initialize(2048);
      return g.generateKeyPair();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
