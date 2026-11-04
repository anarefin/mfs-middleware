package mfs.mw.core.mock.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import mfs.mw.core.mock.contracts.ContractSchemas;
import mfs.mw.core.mock.dto.CoreCommand;
import mfs.mw.core.mock.exception.MwException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

/** The mapper reads the orchestrator's contract samples and refuses what cannot be a command. */
class CoreCommandMapperTest {

  static final JsonMapper JSON = JsonMapper.builder().build();
  static final String DIR = "events/mw-orchestrator/samples/core.command.v1/";

  private final CoreCommandMapper mapper = new CoreCommandMapper(JSON);

  static List<Path> transferSamples() {
    List<Path> all = new java.util.ArrayList<>(ContractSchemas.samples(DIR + "PostTransfer", "valid"));
    all.addAll(ContractSchemas.samples(DIR + "ValidateTransaction", "valid"));
    return all;
  }

  @ParameterizedTest
  @MethodSource("transferSamples")
  void readsEveryValidTransferSample(Path sample) throws Exception {
    CoreCommand cmd = mapper.command(Files.readString(sample));

    if (mapper.transfer(cmd).pinRequired()) {
      assertThat(mapper.transfer(cmd).encryptedPin().ciphertext()).isNotBlank();
    } else {
      assertThat(mapper.transfer(cmd).encryptedPin()).isNull(); // BULK_DISBURSE, ticket 34
    }
    assertThat(mapper.transfer(cmd).debitParty().id()).isNotBlank();
  }

  @Test
  void aTransferWithoutPinIsRejectedUnlessItIsABulkDisbursement() {
    String body =
        "{\"commandType\":\"PostTransfer\",\"commandId\":\"c\",\"txnId\":\"t\",\"payload\":{\"txnType\":\"%s\","
            + "\"debitParty\":{\"type\":\"ORG\",\"id\":\"01311000001\"},\"creditParty\":{\"type\":\"WALLET\","
            + "\"id\":\"01711000001\"},\"amount\":{\"valueMinor\":100,\"currency\":\"BDT\"}}}";
    assertThatThrownBy(() -> mapper.transfer(mapper.command(body.formatted("P2P")))).isInstanceOf(MwException.class);
    assertThat(mapper.transfer(mapper.command(body.formatted("BULK_DISBURSE"))).encryptedPin()).isNull();
  }

  @Test
  void malformedJsonAndIncompleteEnvelopesAreRejected() {
    assertThatThrownBy(() -> mapper.command("{not json")).isInstanceOf(MwException.class);
    assertThatThrownBy(() -> mapper.command("{\"commandType\":\"PostTransfer\"}")).isInstanceOf(MwException.class);
  }

  @Test
  void incompleteTransferPayloadsAreRejected() {
    CoreCommand cmd =
        mapper.command(
            "{\"commandType\":\"PostTransfer\",\"commandId\":\"c\",\"txnId\":\"t\",\"payload\":{\"txnType\":\"P2P\"}}");

    assertThatThrownBy(() -> mapper.transfer(cmd)).isInstanceOf(MwException.class);
  }

  @Test
  void aPayloadOfTheWrongShapeIsRejected() {
    CoreCommand cmd =
        mapper.command(
            "{\"commandType\":\"PostTransfer\",\"commandId\":\"c\",\"txnId\":\"t\",\"payload\":{\"amount\":\"lots\"}}");

    assertThatThrownBy(() -> mapper.transfer(cmd)).isInstanceOf(MwException.class);
  }
}
