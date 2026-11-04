package mfs.mw.checkout.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import mfs.mw.checkout.contracts.ContractSchemas;
import mfs.mw.checkout.dto.EncryptedPin;
import mfs.mw.checkout.dto.NotificationRequest;
import mfs.mw.checkout.dto.SessionStatus;
import mfs.mw.checkout.dto.TxnRequest;
import mfs.mw.checkout.entity.CheckoutSession;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class CheckoutMapperTest {

  static final JsonMapper JSON = JsonMapper.builder().build();
  static final Instant NOW = Instant.parse("2026-11-04T10:00:00Z");
  static final UUID SESSION = UUID.fromString("019a4f6e-1c2d-7a3b-8c4d-5e6f7a8b9c01");

  static CheckoutSession session() {
    return new CheckoutSession(
        SESSION, "tok", "merchant_demo", "01911000001", "ORD-42", 2550, "BDT", "Order 42", "en",
        "https://shop.example.test/ok", "https://shop.example.test/fail", "https://shop.example.test/cancel",
        "https://shop.example.test/ipn", SessionStatus.OPEN, "01711000001", "hash", NOW.plusSeconds(120),
        0, 1, null, null, NOW, NOW.plusSeconds(900), NOW);
  }

  final CheckoutMapper mapper = new CheckoutMapper();

  @Test
  void payIsACanonicalMerchantPayFromTheCustomerWalletToTheMerchantWallet() {
    TxnRequest req =
        mapper.toTxnRequest(session(), "019a4f6e-1c2d-7a3b-8c4d-5e6f7a8b9c0d", "corr-1",
            new EncryptedPin("core-pin-dev-1", "Y2lwaGVy"), NOW);

    JsonNode tree = JSON.valueToTree(req);
    assertThat(ContractSchemas.schema("events/mw-orchestrator/mw.txn.request.v1.schema.json").validate(tree))
        .isEmpty();
    assertThat(tree.path("channel").asString()).isEqualTo("WEB_CHECKOUT");
    assertThat(tree.path("txnType").asString()).isEqualTo("MERCHANT_PAY");
    assertThat(tree.path("idempotencyKey").asString()).isEqualTo("checkout:" + SESSION);
    assertThat(tree.path("initiator").path("type").asString()).isEqualTo("MSISDN");
    assertThat(tree.path("initiator").path("id").asString()).isEqualTo("01711000001");
    assertThat(tree.path("debitParty").path("id").asString()).isEqualTo("01711000001");
    assertThat(tree.path("debitParty").path("walletType").asString()).isEqualTo("CUSTOMER");
    assertThat(tree.path("creditParty").path("id").asString()).isEqualTo("01911000001");
    assertThat(tree.path("creditParty").path("walletType").asString()).isEqualTo("MERCHANT");
    assertThat(tree.path("amount").path("valueMinor").asLong()).isEqualTo(2550L);
    assertThat(tree.path("encryptedPin").path("ciphertext").asString()).isEqualTo("Y2lwaGVy");
    assertThat(tree.path("locale").asString()).isEqualTo("en");
    assertThat(tree.path("metadata").path("checkoutSessionId").asString()).isEqualTo(SESSION.toString());
    assertThat(tree.path("metadata").path("merchantId").asString()).isEqualTo("merchant_demo");
    assertThat(tree.path("metadata").path("orderId").asString()).isEqualTo("ORD-42");
  }

  @Test
  void theOtpIsAnSmsNotificationWithOtpPriorityThatExpiresWithTheOtp() {
    NotificationRequest n =
        mapper.toOtpNotification(session(), "Demo Shop", "123456", 2, NOW.plusSeconds(120), "corr-1");

    JsonNode tree = JSON.valueToTree(n);
    assertThat(ContractSchemas.schema("events/mw-notify/mw.notify.request.v1.schema.json").validate(tree))
        .isEmpty();
    assertThat(tree.path("priority").asString()).isEqualTo("OTP");
    assertThat(tree.path("channels").get(0).asString()).isEqualTo("SMS");
    assertThat(tree.path("channels").size()).isEqualTo(1);
    assertThat(tree.path("recipient").path("msisdn").asString()).isEqualTo("01711000001");
    assertThat(tree.path("templateCode").asString()).isEqualTo("CHECKOUT_OTP");
    assertThat(tree.path("messageId").asString()).isEqualTo("checkout-otp:" + SESSION + ":2");
    assertThat(tree.path("params").path("otp").asString()).isEqualTo("123456");
    assertThat(tree.path("params").path("amount").asString()).isEqualTo("25.50");
    assertThat(tree.path("params").path("merchant").asString()).isEqualTo("Demo Shop");
    assertThat(tree.path("expiresAt").asString()).isEqualTo("2026-11-04T10:02:00Z");
    assertThat(tree.path("locale").asString()).isEqualTo("en");
  }
}
