package mfs.mw.audit.dw.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The schema loader resolves {@code https://contracts.upay.mw/} ids, and the relative {@code $ref}s
 * between envelope, payload and {@code common/} schemas, from {@code mwContractsDir} (spec 4.3 rule
 * 2; ticket 48). core.command.v1 HoldFunds is the worked example: envelope -> HoldFunds payload ->
 * common/defs.v1 EncryptedPin and Amount.
 */
class ContractSchemasTest {

  static final JsonMapper JSON = JsonMapper.builder().build();
  static final String CORE_COMMAND = "events/mw-orchestrator/core.command.v1.schema.json";
  static final String HOLD_FUNDS_SAMPLES =
      "events/mw-orchestrator/samples/core.command.v1/HoldFunds";

  static JsonNode sample(String name) throws Exception {
    return JSON.readTree(
        Files.readString(ContractSchemas.contractsDir().resolve(HOLD_FUNDS_SAMPLES).resolve(name)));
  }

  static List<Error> validateHoldFunds(String sample) throws Exception {
    Schema schema = ContractSchemas.schema(CORE_COMMAND);
    return schema.validate(sample(sample));
  }

  @Test
  void validHoldFundsSamplesPassThroughEnvelopePayloadAndCommonDefs() throws Exception {
    List<Path> valid = ContractSchemas.samples(HOLD_FUNDS_SAMPLES, "valid");
    assertThat(valid).isNotEmpty();
    for (Path p : valid) {
      assertThat(validateHoldFunds(p.getFileName().toString())).as("%s", p).isEmpty();
    }
  }

  @Test
  void holdFundsWithoutEncryptedPinIsRejectedByThePayloadSchema() throws Exception {
    List<Error> errors = validateHoldFunds("invalid-hold-funds-without-pin.json");

    assertThat(errors)
        .extracting(Error::getMessage)
        .anySatisfy(m -> assertThat(m).contains("encryptedPin"));
  }

  @Test
  void holdFundsWithADecimalAmountIsRejectedByTheCommonAmountType() throws Exception {
    List<Error> errors = validateHoldFunds("invalid-decimal-amount.json");

    // networknt has no discriminator, so it also reports the other oneOf branches; the HoldFunds
    // branch's error is the decimal valueMinor.
    assertThat(errors)
        .anySatisfy(
            e -> {
              assertThat(e.getInstanceLocation().toString()).contains("valueMinor");
              assertThat(e.getMessage()).contains("integer expected");
            });
  }

  @Test
  void everyInvalidHoldFundsSampleIsRejected() throws Exception {
    List<Path> invalid = ContractSchemas.samples(HOLD_FUNDS_SAMPLES, "invalid");
    assertThat(invalid).isNotEmpty();
    for (Path p : invalid) {
      assertThat(validateHoldFunds(p.getFileName().toString())).as("%s", p).isNotEmpty();
    }
  }

  @Test
  void schemaIdsMapToFilesUnderMwContractsDir() {
    assertThat(ContractSchemas.fileFor(ContractSchemas.ID_BASE + CORE_COMMAND))
        .isEqualTo(ContractSchemas.contractsDir().resolve(CORE_COMMAND))
        .isRegularFile();
  }
}
