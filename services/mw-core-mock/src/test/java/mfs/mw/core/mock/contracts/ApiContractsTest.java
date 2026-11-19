package mfs.mw.core.mock.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import mfs.mw.core.mock.dto.WalletBalance;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The API of {@code contracts.yml} (ticket 57): what the wallet endpoints serve, serialised as
 * Spring MVC does, validates against {@code core-mock-seed.v1}; a contract-shaped seed body is the
 * one the controller binds.
 */
class ApiContractsTest {

  static final JsonMapper JSON = JsonMapper.builder().build();
  static final String SEED = "api/mw-core-mock/core-mock-seed.v1.openapi.yaml";

  static final String WALLET = "api/mw-core-mock/core-wallet.v1.openapi.yaml";

  static final String CONTROL = "api/mw-core-mock/core-mock-control.v1.openapi.yaml";

  @Test
  void theContractsAreListedAsProduced() {
    assertThat(ContractManifest.producedApis()).containsExactly(SEED, WALLET, CONTROL);
  }

  /** Ticket 103: what the core's mock-control API serves is what its contract says. */
  @Test
  void theMockControlBodiesAreTheContracts() {
    JsonNode reset = JSON.readTree(JSON.writeValueAsString(new mfs.mw.core.mock.dto.ResetResult(
        java.util.List.of(mfs.mw.core.mock.dto.ResetResult.PartyResult.reset("core", "v1")))));
    assertThat(OpenApiSchemas.response(CONTROL, "/mock-control/reset", "post", "200").validate(reset)).isEmpty();
    JsonNode scenario = JSON.readTree(JSON.writeValueAsString(
        mfs.mw.core.mock.dto.MockScenario.of("transfer", mfs.mw.core.mock.dto.MockBehaviour.BUSINESS_ERROR)
            .withCode("INSUFFICIENT_BALANCE").withKey("01711000001").withNextN(1).withIdAndRemaining("S-1", 1)));
    assertThat(OpenApiSchemas.response(CONTROL, "/mock-control/{party}/scenarios", "post", "201").validate(scenario))
        .isEmpty();
    JsonNode parties = JSON.readTree(JSON.writeValueAsString(mfs.mw.core.mock.service.mockcontrol.MockParties.ALL));
    assertThat(OpenApiSchemas.response(CONTROL, "/mock-control/parties", "get", "200").validate(parties)).isEmpty();
  }

  /**
   * The core wallet API's request bodies (ticket 93) refuse what the controller's Bean Validation
   * refuses (CoreWalletControllerTest); its served bodies are checked by CoreWalletRestIT.
   */
  @Test
  void theCoreWalletContractRefusesWhatTheControllerRefuses() {
    var transfer = OpenApiSchemas.component(WALLET, "TransferRequest");
    String ok = """
        {"txnId":"0190a7c4-0000-7000-8000-000000000001","txnType":"P2P","debitParty":{"type":"WALLET","id":"01711000001"},
         "creditParty":{"type":"WALLET","id":"01711000002"},"amount":{"valueMinor":100,"currency":"BDT"},"idempotencyKey":"k"}""";
    assertThat(transfer.validate(JSON.readTree(ok))).isEmpty();
    assertThat(transfer.validate(JSON.readTree(ok.replace("0190a7c4-0000-7000", "0190a7c4-0000-4000")))).as("not a UUIDv7").isNotEmpty();
    assertThat(transfer.validate(JSON.readTree(ok.replace("\"valueMinor\":100", "\"valueMinor\":-1")))).isNotEmpty();
    assertThat(transfer.validate(JSON.readTree(ok.replace("BDT", "USD")))).isNotEmpty();
    var reversal = OpenApiSchemas.component(WALLET, "ReversalRequest");
    assertThat(reversal.validate(JSON.readTree(
        "{\"txnId\":\"0190a7c4-0000-7000-8000-000000000001\",\"originalTxnId\":\"0190a7c4-0000-7000-8000-000000000002\"}")))
        .as("amount optional").isEmpty();
    assertThat(reversal.validate(JSON.readTree("{\"txnId\":\"0190a7c4-0000-7000-8000-000000000001\"}"))).isNotEmpty();
  }

  @Test
  void mwAuthsAssertionKeySetIsConsumedAsItsContractShapesIt() {
    String auth = "api/mw-auth/auth.v1.openapi.yaml";
    assertThat(ContractManifest.consumedApis()).containsExactly(auth);
    JsonNode served = JSON.readTree(
        new com.nimbusds.jose.jwk.JWKSet(mfs.mw.core.mock.security.TestAssertions.KEY.toPublicJWK()).toString());
    assertThat(OpenApiSchemas.response(auth, "/auth/v1/.well-known/assertion-jwks.json", "get", "200").validate(served))
        .as("the test key set the ITs verify with is shaped as mw-auth serves it").isEmpty();
  }

  @Test
  void aServedWalletIsTheContractsWallet() {
    JsonNode body = JSON.readTree(JSON.writeValueAsString(new WalletBalance("01712000001", "CUSTOMER", "ACTIVE", 10, 2, 8, "Rahim Uddin")));
    for (String status : new String[] {"200", "201"}) {
      assertThat(OpenApiSchemas.response(SEED, "/core-mock/v1/wallets/{walletId}", "put", status).validate(body)).isEmpty();
    }
    assertThat(OpenApiSchemas.response(SEED, "/core-mock/v1/wallets/{walletId}", "get", "200").validate(body)).isEmpty();
    JsonNode system = JSON.readTree(JSON.writeValueAsString(new WalletBalance("SETTLEMENT", "SYSTEM", "ACTIVE", 10, 0, 10, null)));
    assertThat(OpenApiSchemas.response(SEED, "/core-mock/v1/wallets/{walletId}", "get", "200").validate(system))
        .as("a wallet without a holder (system wallets) is served with holderName null").isEmpty();
  }

  @Test
  void theContractRefusesWhatTheControllerRefuses() {
    var schema =
        OpenApiSchemas.pointer(SEED, "/paths/~1core-mock~1v1~1wallets~1{walletId}/put/requestBody/content/application~1json/schema");
    assertThat(schema.validate(JSON.readTree("{\"type\":\"AGENT\",\"openingBalanceMinor\":0}"))).isEmpty();
    assertThat(schema.validate(JSON.readTree("{\"type\":\"SYSTEM\",\"openingBalanceMinor\":0}"))).isNotEmpty();
    assertThat(schema.validate(JSON.readTree("{\"type\":\"AGENT\",\"openingBalanceMinor\":-1}"))).isNotEmpty();
    assertThat(schema.validate(JSON.readTree("{\"type\":\"AGENT\",\"openingBalanceMinor\":0,\"holderName\":\"Monir Store\"}"))).isEmpty();
    assertThat(schema.validate(JSON.readTree("{\"type\":\"AGENT\",\"openingBalanceMinor\":0,\"balanceMinor\":500}"))).isEmpty();
    assertThat(schema.validate(JSON.readTree("{\"type\":\"AGENT\",\"openingBalanceMinor\":0,\"balanceMinor\":-1}"))).isNotEmpty();
    assertThat(schema.validate(JSON.readTree("{\"type\":\"AGENT\",\"openingBalanceMinor\":0,\"balanceMinor\":100000000001}")))
        .isNotEmpty();
    assertThat(schema.validate(JSON.readTree("{\"type\":\"AGENT\",\"openingBalanceMinor\":0,\"holderName\":\"\"}"))).isNotEmpty();
    assertThat(schema.validate(JSON.readTree("{\"type\":\"AGENT\",\"openingBalanceMinor\":0,\"holderName\":\"" + "x".repeat(121) + "\"}")))
        .isNotEmpty();
  }
}
