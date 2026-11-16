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

    var p = mapper.transfer(cmd);
    if (!p.authorisationRequired()) {
      assertThat(p.assertion()).isNull(); // BULK_DISBURSE, ticket 34
    } else {
      assertThat(p.assertion()).isNotBlank(); // ticket 72: the assertion is the only authorisation
      assertThat(p.idempotencyKey()).isNotBlank();
    }
    assertThat(p.debitParty().id()).isNotBlank();
  }

  @Test
  void anAssertionWithoutItsIdempotencyKeyIsRejected() {
    String body =
        "{\"schemaVersion\":1,\"commandType\":\"PostTransfer\",\"commandId\":\"c\",\"txnId\":\"t\",\"payload\":{\"txnType\":\"P2P\","
            + "\"debitParty\":{\"type\":\"WALLET\",\"id\":\"01711000001\"},\"creditParty\":{\"type\":\"WALLET\","
            + "\"id\":\"01711000002\"},\"amount\":{\"valueMinor\":100,\"currency\":\"BDT\"},\"assertion\":\"a.b.c\"%s}}";
    assertThatThrownBy(() -> mapper.transfer(mapper.command(body.formatted("")))).isInstanceOf(MwException.class);
    assertThat(mapper.transfer(mapper.command(body.formatted(",\"idempotencyKey\":\"k\""))).assertion()).isEqualTo("a.b.c");
  }

  @Test
  void aTransferWithoutAssertionIsRejectedUnlessItIsABulkDisbursement() {
    String body =
        "{\"schemaVersion\":1,\"commandType\":\"PostTransfer\",\"commandId\":\"c\",\"txnId\":\"t\",\"payload\":{\"txnType\":\"%s\","
            + "\"debitParty\":{\"type\":\"ORG\",\"id\":\"01311000001\"},\"creditParty\":{\"type\":\"WALLET\","
            + "\"id\":\"01711000001\"},\"amount\":{\"valueMinor\":100,\"currency\":\"BDT\"}}}";
    assertThatThrownBy(() -> mapper.transfer(mapper.command(body.formatted("P2P")))).isInstanceOf(MwException.class);
    assertThat(mapper.transfer(mapper.command(body.formatted("BULK_DISBURSE"))).assertion()).isNull();
    // a stray PIN ciphertext (the scheme removed in ticket 72) does not authorise anything
    String withPin = body.formatted("P2P").replace("\"currency\":\"BDT\"}", "\"currency\":\"BDT\"},\"encryptedPin\":{\"keyId\":\"k\",\"ciphertext\":\"ZmFrZQ==\"}");
    assertThatThrownBy(() -> mapper.transfer(mapper.command(withPin))).isInstanceOf(MwException.class);
  }

  @Test
  void aBalanceInquiryNeedsItsAssertionAndOneWalletOnBothSides() {
    String body =
        "{\"schemaVersion\":1,\"commandType\":\"BalanceInquiry\",\"commandId\":\"t:balance\",\"txnId\":\"t\","
            + "\"payload\":{\"txnType\":\"BALANCE\",\"debitParty\":{\"type\":\"WALLET\",\"id\":\"01711000001\"},"
            + "\"creditParty\":{\"type\":\"WALLET\",\"id\":\"%s\"},\"amount\":{\"valueMinor\":0,\"currency\":\"BDT\"}%s}}";
    String auth = ",\"assertion\":\"a.b.c\",\"idempotencyKey\":\"k\"";
    assertThat(mapper.balance(mapper.command(body.formatted("01711000001", auth))).debitParty().id())
        .isEqualTo("01711000001");
    assertThatThrownBy(() -> mapper.balance(mapper.command(body.formatted("01711000001", ""))))
        .isInstanceOf(MwException.class);
    assertThatThrownBy(() -> mapper.balance(mapper.command(body.formatted("01711000002", auth))))
        .isInstanceOf(MwException.class);
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
            "{\"schemaVersion\":1,\"commandType\":\"PostTransfer\",\"commandId\":\"c\",\"txnId\":\"t\",\"payload\":{\"txnType\":\"P2P\"}}");

    assertThatThrownBy(() -> mapper.transfer(cmd)).isInstanceOf(MwException.class);
  }

  @Test
  void aPayloadOfTheWrongShapeIsRejected() {
    CoreCommand cmd =
        mapper.command(
            "{\"schemaVersion\":1,\"commandType\":\"PostTransfer\",\"commandId\":\"c\",\"txnId\":\"t\",\"payload\":{\"amount\":\"lots\"}}");

    assertThatThrownBy(() -> mapper.transfer(cmd)).isInstanceOf(MwException.class);
  }
}
