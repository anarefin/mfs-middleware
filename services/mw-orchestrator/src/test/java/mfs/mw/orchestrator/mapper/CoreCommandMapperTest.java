package mfs.mw.orchestrator.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import mfs.mw.orchestrator.TestData;
import mfs.mw.orchestrator.contracts.ContractSchemas;
import mfs.mw.orchestrator.dto.CoreCommand;
import mfs.mw.orchestrator.dto.TxnRequest;
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
  void validateTransactionIsAValidCommandThatCarriesTheAssertion() {
    CoreCommand c = mapper.validate(rec, TestData.NOW, Duration.ofSeconds(60));

    assertThat(c.commandType()).isEqualTo("ValidateTransaction");
    assertThat(c.commandId()).isEqualTo(rec.txnId() + ":validate");
    assertThat(c.expiresAt()).isEqualTo(TestData.NOW.plusSeconds(60));
    assertThat(json.writeValueAsString(c)).contains(TestData.ASSERTION);
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
    assertThat(json.writeValueAsString(c)).doesNotContain("assertion", TestData.ASSERTION);
    assertThat(ContractSchemas.schema(SCHEMA).validate(json.valueToTree(c))).isEmpty();
  }

  @Test
  void reverseTransactionNeedsAReversalTxnId() {
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> mapper.reverse(rec, TestData.NOW, Duration.ofSeconds(60)))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void theAssertionReachesEveryAuthorisingCommandInsteadOfThePin() {
    TransactionRecord p2p = new RequestMapper(json)
        .toRecord(TestData.withAssertion(TestData.p2p(TestData.txnId(), "key-a", 5_000)), null, TestData.NOW);
    TransactionRecord topup = new RequestMapper(json)
        .toRecord(TestData.withAssertion(TestData.topup(TestData.txnId(), "key-t", 2_000, "01711000009")), null,
            TestData.NOW);

    for (CoreCommand c : java.util.List.of(
        mapper.validate(p2p, TestData.NOW, Duration.ofSeconds(60)),
        mapper.post(p2p, TestData.NOW, Duration.ofSeconds(60)),
        mapper.validate(topup, TestData.NOW, Duration.ofSeconds(60)),
        mapper.hold(topup, TestData.NOW, Duration.ofSeconds(60)))) {
      var payload = json.valueToTree(c).path("payload");
      assertThat(payload.path("assertion").asString()).as(c.commandType()).isEqualTo(TestData.ASSERTION);
      assertThat(payload.has("encryptedPin")).as(c.commandType()).isFalse();
      assertThat(payload.path("idempotencyKey").asString()).as(c.commandType())
          .isEqualTo(c.txnId().equals(topup.txnId().toString()) ? "key-t" : "key-a");
      assertThat(ContractSchemas.schema(SCHEMA).validate(json.valueToTree(c))).as(c.commandType()).isEmpty();
    }
  }

  @Test
  void anOldChannelsPinCiphertextIsDroppedOnIntakeAndNeverReachesTheCore() {
    TxnRequest legacy = json.readValue(
        json.writeValueAsString(TestData.p2p(TestData.txnId(), "key-b", 5_000))
            .replaceFirst("^\\{", "{\"encryptedPin\":{\"keyId\":\"k\",\"ciphertext\":\"ZmFrZQ==\"},"),
        TxnRequest.class);

    TransactionRecord r = new RequestMapper(json).toRecord(legacy, null, TestData.NOW);

    assertThat(json.writeValueAsString(r.request())).doesNotContain("encryptedPin", "ZmFrZQ==");
    assertThat(r.request().assertion()).isEqualTo(TestData.ASSERTION);
    var payload = json.valueToTree(mapper.validate(r, TestData.NOW, Duration.ofSeconds(60))).path("payload");
    assertThat(payload.has("encryptedPin")).isFalse();
  }

  @Test
  void aBulkChildSendsNeitherAssertionNorIdempotencyKey() {
    TransactionRecord bulk = new RequestMapper(json)
        .toRecord(TestData.bulkChild(TestData.txnId(), "key-k", 1_000, "01711000002"), null, TestData.NOW);

    var c = mapper.validate(bulk, TestData.NOW, Duration.ofSeconds(60));
    var payload = json.valueToTree(c).path("payload");
    assertThat(payload.has("assertion")).isFalse();
    assertThat(payload.has("idempotencyKey")).isFalse();
    assertThat(ContractSchemas.schema(SCHEMA).validate(json.valueToTree(c))).isEmpty();
  }

  /** ADR D26: BalanceInquiry is shaped like a transfer, under {@code <txnId>:balance}, on the wallet topic. */
  @Test
  void balanceInquiryIsAValidCommandWithTheAssertionAndAmountZero() {
    TransactionRecord balance = new RequestMapper(json)
        .toRecord(TestData.balance(TestData.txnId(), "key-b", "01711000001"), null, TestData.NOW);

    CoreCommand c = mapper.balanceInquiry(balance, TestData.NOW, Duration.ofSeconds(60));

    assertThat(c.commandType()).isEqualTo("BalanceInquiry");
    assertThat(c.commandId()).isEqualTo(balance.txnId() + ":balance");
    var payload = json.valueToTree(c).path("payload");
    assertThat(payload.path("txnType").asString()).isEqualTo("BALANCE");
    assertThat(payload.path("amount").path("valueMinor").asLong()).isZero();
    assertThat(payload.path("assertion").asString()).isEqualTo(TestData.ASSERTION);
    assertThat(payload.path("idempotencyKey").asString()).isEqualTo("key-b");
    assertThat(payload.path("debitParty")).isEqualTo(payload.path("creditParty"));
    assertThat(ContractSchemas.schema(SCHEMA).validate(json.valueToTree(c))).isEmpty();
    assertThat(mfs.mw.orchestrator.service.CoreTopics.commandTopic(c.commandType())).isEqualTo("core.wallet.command.v1");
  }
}
