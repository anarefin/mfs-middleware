package mfs.mw.adapter.atm.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.Error;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The schema loader resolves {@code https://contracts.bits.mw/} ids, and the relative {@code $ref}s
 * between envelope, payload and {@code common/} schemas, from {@code mwContractsDir} (spec 4.3 rule
 * 2; ticket 48). mw.txn.request.v1 is the worked example: the request -> common/defs.v1 TxnAssertion,
 * Party and Amount (ticket 95b retired core.command.v1, the example before).
 */
class ContractSchemasTest {

  static final JsonMapper JSON = JsonMapper.builder().build();
  static final String TXN_REQUEST = "events/mw-orchestrator/mw.txn.request.v1.schema.json";
  static final String TXN_REQUEST_SAMPLES = "events/mw-orchestrator/samples/mw.txn.request.v1";

  static JsonNode sample(String name) throws Exception {
    return JSON.readTree(
        Files.readString(ContractSchemas.contractsDir().resolve(TXN_REQUEST_SAMPLES).resolve(name)));
  }

  static List<Error> validateTxnRequest(String sample) throws Exception {
    JsonSchema schema = ContractSchemas.schema(TXN_REQUEST);
    return schema.validate(sample(sample));
  }

  @Test
  void validTxnRequestSamplesPassThroughTheSchemaAndCommonDefs() throws Exception {
    List<Path> valid = ContractSchemas.samples(TXN_REQUEST_SAMPLES, "valid");
    assertThat(valid).isNotEmpty();
    for (Path p : valid) {
      assertThat(validateTxnRequest(p.getFileName().toString())).as("%s", p).isEmpty();
    }
  }

  @Test
  void aReversalWithoutItsOriginalIsRejectedByTheSchema() throws Exception {
    List<Error> errors = validateTxnRequest("invalid-reversal-without-original.json");

    assertThat(errors)
        .extracting(Error::getMessage)
        .anySatisfy(m -> assertThat(m).contains("originalTxnId"));
  }

  @Test
  void aDecimalAmountIsRejectedByTheCommonAmountType() throws Exception {
    List<Error> errors = validateTxnRequest("invalid-decimal-amount.json");

    assertThat(errors)
        .anySatisfy(
            e -> {
              assertThat(e.getInstanceLocation().toString()).contains("valueMinor");
              assertThat(e.getMessage()).contains("integer expected");
            });
  }

  @Test
  void everyInvalidTxnRequestSampleIsRejected() throws Exception {
    List<Path> invalid = ContractSchemas.samples(TXN_REQUEST_SAMPLES, "invalid");
    assertThat(invalid).isNotEmpty();
    for (Path p : invalid) {
      assertThat(validateTxnRequest(p.getFileName().toString())).as("%s", p).isNotEmpty();
    }
  }

  @Test
  void schemaIdsMapToFilesUnderMwContractsDir() {
    assertThat(ContractSchemas.fileFor(ContractSchemas.ID_BASE + TXN_REQUEST))
        .isEqualTo(ContractSchemas.contractsDir().resolve(TXN_REQUEST))
        .isRegularFile();
  }
}
