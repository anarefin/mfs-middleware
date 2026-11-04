package mfs.mw.ussd.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import mfs.mw.ussd.contracts.ContractSchemas;
import mfs.mw.ussd.dto.EncryptedPin;
import mfs.mw.ussd.dto.FlowRequest;
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
            new EncryptedPin("core-pin-dev-1", "Y2lwaGVy"));

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
    assertThat(tree.path("encryptedPin").path("ciphertext").asString()).isEqualTo("Y2lwaGVy");
    assertThat(tree.path("locale").asString()).isEqualTo("en");
    assertThat(tree.path("metadata").path("ussdSessionId").asString()).isEqualTo("sess-1");
    assertThat(tree.path("metadata").path("mno").asString()).isEqualTo("robi");
  }
}
