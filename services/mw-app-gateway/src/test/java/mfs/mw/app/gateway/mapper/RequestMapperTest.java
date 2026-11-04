package mfs.mw.app.gateway.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import mfs.mw.app.gateway.contracts.ContractSchemas;
import mfs.mw.app.gateway.dto.Amount;
import mfs.mw.app.gateway.dto.AppUser;
import mfs.mw.app.gateway.dto.EncryptedPin;
import mfs.mw.app.gateway.dto.TransactionRequest;
import mfs.mw.app.gateway.dto.TransactionView;
import mfs.mw.app.gateway.dto.TxnRequest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The canonical request is valid against the orchestrator's contract and keeps the PIN as sent. */
class RequestMapperTest {

  static final JsonMapper JSON = JsonMapper.builder().build();
  static final String TXN_ID = "019a4f6e-1c2d-7a3b-8c4d-5e6f7a8b9c0d";
  static final Instant NOW = Instant.parse("2026-11-04T06:00:00Z");

  final RequestMapper mapper = new RequestMapper();

  TransactionRequest body(Map<String, String> attributes) {
    return new TransactionRequest(
        "P2P",
        new TransactionRequest.CounterParty(null, "01711000002"),
        new Amount(12_345L, "BDT"),
        new EncryptedPin("core-pin-1", "b3BhcXVlLWNpcGhlcnRleHQ="),
        "en",
        attributes);
  }

  @Test
  void requestFollowsTheContractWithThePayerFromTheToken() {
    TxnRequest req =
        mapper.toRequest(
            new AppUser("01711000001", "CUSTOMER_APP", "CUSTOMER"), "key-1",
            body(Map.of("note", "lunch")), TXN_ID, "corr-1", NOW);

    JsonNode tree = JSON.valueToTree(req);
    assertThat(ContractSchemas.schema("events/mw-orchestrator/mw.txn.request.v1.schema.json").validate(tree))
        .isEmpty();
    assertThat(tree.path("initiator").path("type").asString()).isEqualTo("MSISDN");
    assertThat(tree.path("initiator").path("id").asString()).isEqualTo("01711000001");
    assertThat(tree.path("debitParty").path("type").asString()).isEqualTo("WALLET");
    assertThat(tree.path("debitParty").path("walletType").asString()).isEqualTo("CUSTOMER");
    assertThat(tree.path("creditParty").path("id").asString()).isEqualTo("01711000002");
    assertThat(tree.path("encryptedPin").path("ciphertext").asString()).isEqualTo("b3BhcXVlLWNpcGhlcnRleHQ=");
    assertThat(tree.path("attributes").path("note").asString()).isEqualTo("lunch");
    assertThat(tree.path("requestedAt").asString()).isEqualTo("2026-11-04T06:00:00Z");
  }

  @Test
  void emptyAttributesAreLeftOutAndTheAcceptedViewIsReceived() {
    TxnRequest req =
        mapper.toRequest(
            new AppUser("01711000001", "CUSTOMER_APP", "CUSTOMER"), "key-1", body(Map.of()), TXN_ID,
            "corr-1", NOW);
    assertThat(req.attributes()).isNull();

    TransactionView v = mapper.accepted(req);
    assertThat(v.status()).isEqualTo("RECEIVED");
    assertThat(v.txnId()).isEqualTo(TXN_ID);
    assertThat(v.amount().valueMinor()).isEqualTo(12_345L);
    assertThat(JSON.writeValueAsString(v)).doesNotContain("ciphertext");
  }
}
