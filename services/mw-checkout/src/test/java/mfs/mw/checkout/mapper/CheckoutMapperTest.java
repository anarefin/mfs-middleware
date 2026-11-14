package mfs.mw.checkout.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import mfs.mw.checkout.contracts.ContractSchemas;
import mfs.mw.checkout.dto.EncryptedPin;
import mfs.mw.checkout.contracts.OpenApiSchemas;
import mfs.mw.checkout.dto.TxnAuthorisationRequest;
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
        "https://shop.example.test/ipn", SessionStatus.OPEN, "01711000001",
        1, null, null, NOW, NOW.plusSeconds(900), NOW);
  }

  final CheckoutMapper mapper = new CheckoutMapper();

  static final String ASSERTION = "eyJhbGciOiJFUzI1NiJ9.eyJzdWIiOiIwMTcxMTAwMDAwMSJ9.c2lnbmF0dXJl";

  @Test
  void payIsACanonicalMerchantPayFromTheCustomerWalletToTheMerchantWallet() {
    TxnRequest req =
        mapper.toTxnRequest(session(), "019a4f6e-1c2d-7a3b-8c4d-5e6f7a8b9c0d", "corr-1", NOW)
            .withAssertion(ASSERTION);

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
    assertThat(tree.path("assertion").asString()).isEqualTo(ASSERTION);
    assertThat(tree.has("encryptedPin")).as("no PIN material on Kafka").isFalse();
    assertThat(tree.path("locale").asString()).isEqualTo("en");
    assertThat(tree.path("metadata").path("checkoutSessionId").asString()).isEqualTo(SESSION.toString());
    assertThat(tree.path("metadata").path("merchantId").asString()).isEqualTo("merchant_demo");
    assertThat(tree.path("metadata").path("orderId").asString()).isEqualTo("ORD-42");
  }

  @Test
  void theAuthorisationCarriesExactlyThePublishedValues() {
    TxnRequest req = mapper.toTxnRequest(session(), "019a4f6e-1c2d-7a3b-8c4d-5e6f7a8b9c0d", "corr-1", NOW);
    EncryptedPin pin = new EncryptedPin("pin-transport.v1", "Y2lwaGVy");
    TxnAuthorisationRequest a = mapper.toAuthorisation(req, pin);

    assertThat(a.msisdn()).isEqualTo(req.initiator().id());
    assertThat(a.channel()).isEqualTo(req.channel()).isEqualTo("WEB_CHECKOUT");
    assertThat(a.encryptedPin()).isSameAs(pin);
    assertThat(a.txn().txnType()).isEqualTo(req.txnType());
    assertThat(a.txn().amount()).isEqualTo(req.amount().valueMinor()).isEqualTo(2550L);
    assertThat(a.txn().currency()).isEqualTo(req.amount().currency());
    assertThat(a.txn().payer()).isEqualTo(
        new TxnAuthorisationRequest.TxnParty(req.debitParty().type(), req.debitParty().id()));
    assertThat(a.txn().payee()).isEqualTo(
        new TxnAuthorisationRequest.TxnParty(req.creditParty().type(), req.creditParty().id()));
    assertThat(a.txn().idempotencyKey()).isEqualTo(req.idempotencyKey()).isEqualTo("checkout:" + SESSION);

    JsonNode tree = JSON.valueToTree(a);
    assertThat(OpenApiSchemas.component("api/mw-auth/auth-internal.v1.openapi.yaml", "TxnAuthorisationRequest")
        .validate(tree)).isEmpty();
  }

  @Test
  void theOtpAmountIsTakaWithTwoDecimals() {
    assertThat(CheckoutMapper.taka(2550)).isEqualTo("25.50");
    assertThat(CheckoutMapper.taka(100000)).isEqualTo("1000.00");
  }
}
