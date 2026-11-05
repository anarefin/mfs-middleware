package mfs.mw.orchestrator.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import mfs.mw.orchestrator.TestData;
import mfs.mw.orchestrator.contracts.ContractSchemas;
import mfs.mw.orchestrator.dto.ErrorInfo;
import mfs.mw.orchestrator.dto.StatusMessage;
import mfs.mw.orchestrator.entity.TransactionRecord;
import mfs.mw.orchestrator.service.saga.Status;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class StatusMapperTest {

  private static final String SCHEMA = "events/mw-orchestrator/mw.txn.status.v1.schema.json";

  private final JsonMapper json = JsonMapper.builder().build();
  private final RequestMapper requests = new RequestMapper(json);
  private final StatusMapper mapper = new StatusMapper();

  private TransactionRecord received() {
    return requests.toRecord(TestData.p2p(TestData.txnId(), "key-1", 5_000), "gw-1", TestData.NOW);
  }

  @Test
  void aCompletedTransactionIsAValidStatusMessageWithFeeAndCoreRef() {
    TransactionRecord done =
        received().moved(Status.POSTING, TestData.NOW, null).withCore("CORE-1", 500L)
            .moved(Status.COMPLETED, TestData.NOW.plusSeconds(1), null);
    StatusMessage msg = mapper.toStatus(done);

    assertThat(msg.status()).isEqualTo("COMPLETED");
    assertThat(msg.previousStatus()).isEqualTo("POSTING");
    assertThat(msg.fee().valueMinor()).isEqualTo(500);
    assertThat(msg.coreRef()).isEqualTo("CORE-1");
    assertThat(msg.updatedAt()).isEqualTo(TestData.NOW.plusSeconds(1));
    assertThat(ContractSchemas.schema(SCHEMA).validate(json.valueToTree(msg))).isEmpty();
  }

  @Test
  void aRejectedTransactionCarriesItsError() {
    ErrorInfo error = new ErrorInfo("MW-CORE-INSUFFICIENT_BALANCE", "Insufficient balance", null, false);
    StatusMessage msg = mapper.toStatus(received().moved(Status.REJECTED, TestData.NOW, error));

    assertThat(msg.error()).isEqualTo(error);
    assertThat(ContractSchemas.schema(SCHEMA).validate(json.valueToTree(msg))).isEmpty();
  }

  @Test
  void aDuplicateAnswerNamesTheRequestThatAskedAgain() {
    String again = TestData.txnId();
    TransactionRecord rec = received();
    StatusMessage msg = mapper.toDuplicateStatus(rec, again);

    assertThat(msg.txnId()).isEqualTo(rec.txnId().toString());
    assertThat(msg.attributes()).containsEntry("requestTxnId", again).containsEntry("duplicate", "true");
    assertThat(ContractSchemas.schema(SCHEMA).validate(json.valueToTree(msg))).isEmpty();
  }

  @Test
  void aRequestRefusedForReuseOfItsKeyIsRejectedUnderItsOwnTxnId() {
    var req = TestData.p2p(TestData.txnId(), "key-1", 9_000);
    ErrorInfo error = new ErrorInfo("MW-IDEM-001", "Idempotency key reused with a different request", null, false);
    StatusMessage msg = mapper.toRefusedStatus(req, error, TestData.NOW);

    assertThat(msg.txnId()).isEqualTo(req.txnId());
    assertThat(msg.status()).isEqualTo("REJECTED");
    assertThat(msg.previousStatus()).isNull();
    assertThat(ContractSchemas.schema(SCHEMA).validate(json.valueToTree(msg))).isEmpty();
  }

  /** Ticket 50: a repeat answered from the Valkey lock before the first request committed. */
  @Test
  void anInFlightDuplicateIsReceivedUnderTheFirstRequestsTxnId() {
    String first = TestData.txnId();
    var again = TestData.p2p(TestData.txnId(), "key-1", 5_000);
    StatusMessage msg = mapper.toInFlightDuplicateStatus(again, java.util.UUID.fromString(first), TestData.NOW);

    assertThat(msg.txnId()).isEqualTo(first);
    assertThat(msg.status()).isEqualTo("RECEIVED");
    assertThat(msg.attributes()).containsEntry("requestTxnId", again.txnId()).containsEntry("duplicate", "true");
    assertThat(ContractSchemas.schema(SCHEMA).validate(json.valueToTree(msg))).isEmpty();
  }
}
