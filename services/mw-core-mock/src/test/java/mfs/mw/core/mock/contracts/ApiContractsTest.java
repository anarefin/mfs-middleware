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

  @Test
  void theContractIsListedAsProduced() {
    assertThat(ContractManifest.producedApis()).containsExactly(SEED);
  }

  @Test
  void aServedWalletIsTheContractsWallet() {
    JsonNode body = JSON.readTree(JSON.writeValueAsString(new WalletBalance("01712000001", "CUSTOMER", "ACTIVE", 10, 2, 8)));
    for (String status : new String[] {"200", "201"}) {
      assertThat(OpenApiSchemas.response(SEED, "/core-mock/v1/wallets/{walletId}", "put", status).validate(body)).isEmpty();
    }
    assertThat(OpenApiSchemas.response(SEED, "/core-mock/v1/wallets/{walletId}", "get", "200").validate(body)).isEmpty();
  }

  @Test
  void theContractRefusesWhatTheControllerRefuses() {
    var schema =
        OpenApiSchemas.pointer(SEED, "/paths/~1core-mock~1v1~1wallets~1{walletId}/put/requestBody/content/application~1json/schema");
    assertThat(schema.validate(JSON.readTree("{\"type\":\"AGENT\",\"openingBalanceMinor\":0}"))).isEmpty();
    assertThat(schema.validate(JSON.readTree("{\"type\":\"SYSTEM\",\"openingBalanceMinor\":0}"))).isNotEmpty();
    assertThat(schema.validate(JSON.readTree("{\"type\":\"AGENT\",\"openingBalanceMinor\":-1}"))).isNotEmpty();
  }
}
