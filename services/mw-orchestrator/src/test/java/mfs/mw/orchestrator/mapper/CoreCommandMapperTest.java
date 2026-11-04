package mfs.mw.orchestrator.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import mfs.mw.orchestrator.TestData;
import mfs.mw.orchestrator.contracts.ContractSchemas;
import mfs.mw.orchestrator.dto.CoreCommand;
import mfs.mw.orchestrator.entity.TransactionRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class CoreCommandMapperTest {

  private static final String SCHEMA = "events/mw-orchestrator/core.command.v1.schema.json";

  private final JsonMapper json = JsonMapper.builder().build();
  private final CoreCommandMapper mapper = new CoreCommandMapper();
  private final TransactionRecord rec =
      new RequestMapper(json).toRecord(TestData.p2p(TestData.txnId(), "key-1", 5_000), null, TestData.NOW);

  @Test
  void commandIdIsTheTxnIdPlusTheStep() {
    assertThat(mapper.commandId(rec.txnId().toString(), "validate")).isEqualTo(rec.txnId() + ":validate");
    assertThat(mapper.commandId(rec.txnId().toString(), "post")).isEqualTo(rec.txnId() + ":post");
  }

  @Test
  void validateTransactionIsAValidCommandThatCarriesThePinCiphertext() {
    CoreCommand c = mapper.validate(rec, TestData.NOW, Duration.ofSeconds(60));

    assertThat(c.commandType()).isEqualTo("ValidateTransaction");
    assertThat(c.commandId()).isEqualTo(rec.txnId() + ":validate");
    assertThat(c.expiresAt()).isEqualTo(TestData.NOW.plusSeconds(60));
    assertThat(json.writeValueAsString(c)).contains("ZmFrZS1jaXBoZXJ0ZXh0");
    assertThat(ContractSchemas.schema(SCHEMA).validate(json.valueToTree(c))).isEmpty();
  }

  @Test
  void postTransferIsAValidCommandWithItsOwnCommandId() {
    CoreCommand c = mapper.post(rec, TestData.NOW, Duration.ofSeconds(60));

    assertThat(c.commandType()).isEqualTo("PostTransfer");
    assertThat(c.commandId()).isEqualTo(rec.txnId() + ":post");
    assertThat(ContractSchemas.schema(SCHEMA).validate(json.valueToTree(c))).isEmpty();
  }

  @Test
  void reverseTransactionGoesUnderTheReversalTxnIdAndNamesTheOriginal() {
    java.util.UUID reversal = java.util.UUID.fromString(TestData.txnId());
    TransactionRecord completed = rec.withCore("CORE-1", 500L).withReversal(reversal);

    CoreCommand c = mapper.reverse(completed, TestData.NOW, Duration.ofSeconds(60));

    assertThat(c.commandType()).isEqualTo("ReverseTransaction");
    assertThat(c.txnId()).isEqualTo(reversal.toString());
    assertThat(c.commandId()).isEqualTo(reversal + ":reverse");
    var payload = json.valueToTree(c).path("payload");
    assertThat(payload.path("originalTxnId").asString()).isEqualTo(rec.txnId().toString());
    assertThat(payload.path("amount").path("valueMinor").asLong()).isEqualTo(5_000);
    assertThat(payload.path("fee").path("valueMinor").asLong()).isEqualTo(500);
    assertThat(json.writeValueAsString(c)).doesNotContain("encryptedPin");
    assertThat(ContractSchemas.schema(SCHEMA).validate(json.valueToTree(c))).isEmpty();
  }

  @Test
  void creditWalletIsAValidCommandForTheCreditPartyWithoutPin() {
    TransactionRecord addMoney =
        new RequestMapper(json)
            .toRecord(TestData.addMoneyBank(TestData.txnId(), "key-c", 50_000, "1234567890123"), null, TestData.NOW);

    CoreCommand c = mapper.credit(addMoney, TestData.NOW, Duration.ofSeconds(60));

    assertThat(c.commandType()).isEqualTo("CreditWallet");
    assertThat(c.commandId()).isEqualTo(addMoney.txnId() + ":credit");
    var payload = json.valueToTree(c).path("payload");
    assertThat(payload.path("txnType").asString()).isEqualTo("ADD_MONEY_BANK");
    assertThat(payload.path("creditParty").path("id").asString()).isEqualTo("01711000001");
    assertThat(payload.path("debitParty").path("type").asString()).isEqualTo("BANK_ACCOUNT");
    assertThat(payload.path("amount").path("valueMinor").asLong()).isEqualTo(50_000);
    assertThat(json.writeValueAsString(c)).doesNotContain("encryptedPin", "ZmFrZS1jaXBoZXJ0ZXh0");
    assertThat(ContractSchemas.schema(SCHEMA).validate(json.valueToTree(c))).isEmpty();
  }

  @Test
  void reverseTransactionNeedsAReversalTxnId() {
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> mapper.reverse(rec, TestData.NOW, Duration.ofSeconds(60)))
        .isInstanceOf(IllegalStateException.class);
  }
}
