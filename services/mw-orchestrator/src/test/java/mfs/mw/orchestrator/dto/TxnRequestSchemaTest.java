package mfs.mw.orchestrator.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import mfs.mw.orchestrator.contracts.ContractSchemas;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

/** The service's own request type reads every valid sample of the contract it owns. */
class TxnRequestSchemaTest {

  static final JsonMapper JSON = JsonMapper.builder().build();
  static final String SAMPLES = "events/mw-orchestrator/samples/mw.txn.request.v1";

  static List<Path> validSamples() {
    return ContractSchemas.samples(SAMPLES, "valid");
  }

  @ParameterizedTest
  @MethodSource("validSamples")
  void readsEverySample(Path sample) throws Exception {
    TxnRequest r = JSON.readValue(Files.readString(sample), TxnRequest.class);
    assertThat(r.txnId()).isNotBlank();
    assertThat(r.amount().valueMinor()).isPositive();
    assertThat(r.requestedAt()).isNotNull();
    assertThat(r.initiator().id()).isNotBlank();
  }

  @Test
  void withoutPinDropsOnlyTheCiphertext() {
    TxnRequest r =
        JSON.readValue(
            """
            {"schemaVersion":1,"txnId":"t","idempotencyKey":"k","correlationId":"c","channel":"USSD",
             "txnType":"P2P","amount":{"valueMinor":5,"currency":"BDT"},
             "encryptedPin":{"keyId":"k1","ciphertext":"abc"},"requestedAt":"2026-11-03T08:15:30Z"}
            """,
            TxnRequest.class);
    TxnRequest stripped = r.withoutPin();
    assertThat(stripped.encryptedPin()).isNull();
    assertThat(JSON.writeValueAsString(stripped)).doesNotContain("abc");
    assertThat(stripped.amount()).isEqualTo(r.amount());
  }
}
