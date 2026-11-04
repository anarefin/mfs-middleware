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
}
