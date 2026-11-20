package mfs.mw.core.mock.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import mfs.mw.core.mock.dto.CoreCommand;
import mfs.mw.core.mock.exception.MwException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * The mapper reads the commands the REST API builds (ticket 95b: the core.command.v1 contract and its
 * samples are retired) and refuses what cannot be a command.
 */
class CoreCommandMapperTest {

  static final JsonMapper JSON = JsonMapper.builder().build();

  private final CoreCommandMapper mapper = new CoreCommandMapper(JSON);

  static List<String> transferSamples() {
    String p2p = "{\"schemaVersion\":1,\"commandType\":\"PostTransfer\",\"commandId\":\"t1:rest:transfer\",\"txnId\":\"t1\","
        + "\"payload\":{\"txnType\":\"P2P\",\"debitParty\":{\"type\":\"WALLET\",\"id\":\"01711000001\"},"
        + "\"creditParty\":{\"type\":\"WALLET\",\"id\":\"01711000002\"},\"amount\":{\"valueMinor\":10000,\"currency\":\"BDT\"},"
        + "\"assertion\":\"a.b.c\",\"idempotencyKey\":\"k-1\"}}";
    String validate = p2p.replace("PostTransfer", "ValidateTransaction").replace(":rest:transfer", ":rest:validate");
    String bulk = "{\"schemaVersion\":1,\"commandType\":\"PostTransfer\",\"commandId\":\"t2:rest:transfer\",\"txnId\":\"t2\","
        + "\"payload\":{\"txnType\":\"BULK_DISBURSE\",\"debitParty\":{\"type\":\"ORG\",\"id\":\"01311000001\"},"
        + "\"creditParty\":{\"type\":\"WALLET\",\"id\":\"01711000002\"},\"amount\":{\"valueMinor\":500,\"currency\":\"BDT\"}}}";
    return List.of(p2p, validate, bulk);
  }

  @ParameterizedTest
  @MethodSource("transferSamples")
  void readsEveryTransferShape(String body) {
    CoreCommand cmd = mapper.command(body);

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
