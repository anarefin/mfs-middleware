package mfs.mw.ussd.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import mfs.mw.ussd.contracts.ContractSchemas;
import mfs.mw.ussd.dto.EncryptedPin;
import mfs.mw.ussd.dto.FlowRequest;
import mfs.mw.ussd.dto.TxnAuthorisationRequest;
import mfs.mw.ussd.dto.TxnRequest;
import mfs.mw.ussd.dto.UssdSession;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class TxnRequestMapperTest {

  static final JsonMapper JSON = JsonMapper.builder().build();
  static final Instant NOW = Instant.parse("2026-11-04T10:00:00Z");

  @Test
  void p2pIsACanonicalRequestFromTheDiallingWalletToTheEnteredOne() {
    UssdSession s =
        new UssdSession("robi", "sess-1", "01712345678", "main", 1, "en", "p2p.send", List.of("main:1"),
            Map.of(), 0, List.of(), false, NOW);
    FlowRequest f =
        new FlowRequest("P2P", s, "main:1", Map.of("creditParty", "01812345678", "amount", "500.50"),
            new EncryptedPin("PIN_TRANSPORT.v1", "Y2lwaGVy"));

    TxnRequest req =
        new TxnRequestMapper().toRequest(f, "019a4f6e-1c2d-7a3b-8c4d-5e6f7a8b9c0d", "k".repeat(64), "corr-1", NOW);

    JsonNode tree = JSON.valueToTree(req);
    assertThat(ContractSchemas.schema("events/mw-orchestrator/mw.txn.request.v1.schema.json").validate(tree))
        .isEmpty();
    assertThat(tree.path("channel").asString()).isEqualTo("USSD");
    assertThat(tree.path("txnType").asString()).isEqualTo("P2P");
    assertThat(tree.path("initiator").path("id").asString()).isEqualTo("01712345678");
    assertThat(tree.path("debitParty").path("id").asString()).isEqualTo("01712345678");
    assertThat(tree.path("creditParty").path("id").asString()).isEqualTo("01812345678");
    assertThat(tree.path("creditParty").path("walletType").asString()).isEqualTo("CUSTOMER");
    assertThat(tree.path("amount").path("valueMinor").asLong()).isEqualTo(50_050L);
    // the PIN goes to mw-auth only; the published request carries mw-auth's assertion (ticket 72)
    assertThat(tree.has("encryptedPin")).isFalse();
    assertThat(tree.has("assertion")).isFalse();
    assertThat(JSON.valueToTree(req.withAssertion("a.b.c")).path("assertion").asString()).isEqualTo("a.b.c");
    assertThat(tree.path("locale").asString()).isEqualTo("en");
    assertThat(tree.path("metadata").path("ussdSessionId").asString()).isEqualTo("sess-1");
    assertThat(tree.path("metadata").path("mno").asString()).isEqualTo("robi");
  }

  static JsonNode valid(String flow, Map<String, String> fields) {
    UssdSession s =
        new UssdSession("robi", "sess-2", "01712345678", "main", 2, "bn", "x.send", List.of("main:2"),
            Map.of(), 0, List.of(), false, NOW);
    TxnRequest req =
        new TxnRequestMapper()
            .toRequest(new FlowRequest(flow, s, "main:2", fields, new EncryptedPin("PIN_TRANSPORT.v1", "Y2lwaGVy")),
                "019a4f6e-1c2d-7a3b-8c4d-5e6f7a8b9c0e", "k".repeat(64), "corr-2", NOW);
    JsonNode tree = JSON.valueToTree(req);
    assertThat(ContractSchemas.schema("events/mw-orchestrator/mw.txn.request.v1.schema.json").validate(tree))
        .isEmpty();
    assertThat(tree.path("channel").asString()).isEqualTo("USSD");
    assertThat(tree.path("txnType").asString()).isEqualTo(flow);
    assertThat(tree.path("debitParty").path("walletType").asString()).isEqualTo("CUSTOMER");
    return tree;
  }

  @Test
  void cashOutPaysTheAgentsWallet() {
    JsonNode tree = valid("CASH_OUT", Map.of("creditParty", "01812345678", "amount", "500.00"));
    assertThat(tree.path("creditParty").path("type").asString()).isEqualTo("WALLET");
    assertThat(tree.path("creditParty").path("walletType").asString()).isEqualTo("AGENT");
    assertThat(tree.path("partner").isMissingNode()).isTrue();
  }

  @Test
  void topUpRechargesTheMsisdnAtTheOperator() {
    JsonNode tree =
        valid("TOPUP", Map.of("creditParty", "01912345678", "amount", "20.00", "partnerId", "gp", "productCode", "PREPAID"));
    assertThat(tree.path("creditParty").path("type").asString()).isEqualTo("MSISDN");
    assertThat(tree.path("creditParty").path("id").asString()).isEqualTo("01912345678");
    assertThat(tree.path("amount").path("valueMinor").asLong()).isEqualTo(2_000L);
    assertThat(tree.path("partner").path("partnerId").asString()).isEqualTo("gp");
    assertThat(tree.path("partner").path("productCode").asString()).isEqualTo("PREPAID");
  }

  @Test
  void billPayPaysTheBillerAccountThroughTheAggregator() {
    JsonNode tree =
        valid("BILL_PAY", Map.of("creditParty", "1234567", "amount", "667.00", "partnerId", "billagg", "billerCode", "DESCO"));
    assertThat(tree.path("creditParty").path("type").asString()).isEqualTo("BILLER_ACCOUNT");
    assertThat(tree.path("creditParty").path("id").asString()).isEqualTo("1234567");
    assertThat(tree.path("amount").path("valueMinor").asLong()).isEqualTo(66_700L);
    assertThat(tree.path("partner").path("partnerId").asString()).isEqualTo("billagg");
    assertThat(tree.path("partner").path("productCode").asString()).isEqualTo("BILL");
    assertThat(tree.path("partner").path("billerCode").asString()).isEqualTo("DESCO");
  }

  /**
   * Decision of ticket 71: mw-auth hashes exactly the values the request then carries (payer =
   * debitParty, payee = creditParty, amount = valueMinor, the same idempotency key and channel), or
   * the core's txnHash check fails. One case per flow.
   */
  @Test
  void theAuthorisationCarriesExactlyThePublishedValues() {
    EncryptedPin pin = new EncryptedPin("PIN_TRANSPORT.v1", "Y2lwaGVy");
    UssdSession s =
        new UssdSession("robi", "sess-4", "01712345678", "main", 2, "bn", "x.send", List.of(), Map.of(), 0, List.of(),
            false, NOW);
    TxnRequestMapper mapper = new TxnRequestMapper();
    for (FlowRequest f : List.of(
        new FlowRequest("P2P", s, "p", Map.of("creditParty", "01812345678", "amount", "500.50"), pin),
        new FlowRequest("CASH_OUT", s, "p", Map.of("creditParty", "01812345678", "amount", "100"), pin),
        new FlowRequest("TOPUP", s, "p",
            Map.of("creditParty", "01912345678", "amount", "20", "partnerId", "gp", "productCode", "PREPAID"), pin),
        new FlowRequest("BILL_PAY", s, "p",
            Map.of("creditParty", "1234567", "amount", "667", "partnerId", "billagg", "billerCode", "DESCO"), pin))) {
      TxnRequest req = mapper.toRequest(f, "019a4f6e-1c2d-7a3b-8c4d-5e6f7a8b9c0f", "k".repeat(64), "corr-4", NOW);

      TxnAuthorisationRequest a = mapper.authorisation(req, pin);

      assertThat(a.msisdn()).isEqualTo("01712345678");
      assertThat(a.channel()).isEqualTo(req.channel()).isEqualTo("USSD");
      assertThat(a.encryptedPin()).isEqualTo(pin);
      assertThat(a.txn().txnType()).isEqualTo(req.txnType());
      assertThat(a.txn().amount()).isEqualTo(req.amount().valueMinor());
      assertThat(a.txn().currency()).isEqualTo(req.amount().currency());
      assertThat(a.txn().payer()).isEqualTo(
          new TxnAuthorisationRequest.TxnParty(req.debitParty().type(), req.debitParty().id()));
      assertThat(a.txn().payee()).isEqualTo(
          new TxnAuthorisationRequest.TxnParty(req.creditParty().type(), req.creditParty().id()));
      assertThat(a.txn().idempotencyKey()).isEqualTo(req.idempotencyKey());
    }
  }

  @Test
  void aFlowWithoutItsPartnerFieldsIsRefused() {
    UssdSession s =
        new UssdSession("robi", "sess-3", "01712345678", "main", 2, "bn", "x", List.of(), Map.of(), 0, List.of(), false, NOW);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> new TxnRequestMapper().toRequest(
                new FlowRequest("TOPUP", s, "p", Map.of("creditParty", "01912345678", "amount", "20.00"), null),
                "t", "k", "c", NOW))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("TOPUP needs");
  }
}
