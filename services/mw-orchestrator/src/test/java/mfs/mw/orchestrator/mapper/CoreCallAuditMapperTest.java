package mfs.mw.orchestrator.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import mfs.mw.orchestrator.TestData;
import mfs.mw.orchestrator.contracts.ContractManifest;
import mfs.mw.orchestrator.contracts.ContractSchemas;
import mfs.mw.orchestrator.dto.AuditEvent;
import mfs.mw.orchestrator.dto.CoreCommand;
import mfs.mw.orchestrator.dto.CoreReply;
import mfs.mw.orchestrator.entity.TransactionRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Ticket 130b (FRS AUD-01, AUD-04): one CORE_CALL hop per core call of a saga step. */
class CoreCallAuditMapperTest {

  private static final String AUDIT = "events/mw-audit-dw/mw.audit.event.v1.schema.json";
  private static final JsonMapper JSON = ContractManifest.serviceJsonMapper();

  private final TransactionRecord rec =
      new RequestMapper(JSON).toRecord(TestData.p2p(TestData.txnId(), "key-1", 5_000), "gw-1", TestData.NOW);
  private final CoreCommand validate = new CoreCommandMapper().validate(rec, TestData.NOW, Duration.ofSeconds(60));

  private JsonNode published(AuditEvent e) {
    return JSON.readTree(JSON.writeValueAsString(e));
  }

  @Test
  void anAnsweredCallIsASuccessfulOutboundCoreHopWithItsLatencyAndBothBodies() {
    JsonNode answer = JSON.readTree("{\"coreTxnId\":\"CORE-1\",\"fee\":{\"valueMinor\":500,\"currency\":\"BDT\"}}");
    CoreReply reply = new CoreReply("ValidateTransaction", validate.commandId(), validate.txnId(),
        CoreReply.Kind.ANSWERED, 200, null, answer);

    AuditEvent e = new AuditMapper().coreCall(validate, reply, TestData.NOW, 42);
    JsonNode body = published(e);

    assertThat(ContractSchemas.schema(AUDIT).validate(body)).as(body.toString()).isEmpty();
    assertThat(e.action()).isEqualTo("core.call.completed");
    assertThat(e.hop()).isEqualTo("CORE_CALL");
    assertThat(e.direction()).isEqualTo("OUT");
    assertThat(e.durationMs()).isEqualTo(42L);
    assertThat(e.outcome()).isEqualTo("SUCCESS");
    assertThat(e.txnId()).isEqualTo(rec.txnId().toString());
    assertThat(e.resource()).isEqualTo("txn/" + rec.txnId());
    assertThat(e.at()).isEqualTo(TestData.NOW);
    assertThat(e.after()).containsEntry("commandType", "ValidateTransaction")
        .containsEntry("kind", "ANSWERED").containsEntry("httpStatus", "200");
    assertThat(e.payload()).extracting(AuditEvent.Payload::direction).containsExactly("OUT", "IN");
    assertThat(e.payload().get(1).body()).contains("CORE-1");
  }

  @Test
  void theRequestBodyNeverCarriesTheAssertionOrAClearMsisdn() {
    AuditEvent e = new AuditMapper().coreCall(validate, CoreReply.ambiguous(validate, 0), TestData.NOW, 7);

    String all = JSON.writeValueAsString(e);
    assertThat(all).doesNotContain(TestData.ASSERTION).doesNotContain("01711000001");
    assertThat(e.payload().get(0).body()).contains("ValidateTransaction").contains("5000");
  }

  @Test
  void aRefusedOrUnknownCallIsAFailureWithTheCoreCodeAndNoAnswerBodyWhenThereIsNone() {
    CoreReply refused = new CoreReply("ValidateTransaction", validate.commandId(), validate.txnId(),
        CoreReply.Kind.REFUSED, 422, "MW-CORE-INSUFFICIENT_BALANCE",
        JSON.readTree("{\"code\":\"MW-CORE-INSUFFICIENT_BALANCE\",\"holderName\":\"Rahim Uddin\",\"msisdn\":\"01711000001\"}"));

    AuditEvent failed = new AuditMapper().coreCall(validate, refused, TestData.NOW, 3);
    assertThat(failed.outcome()).isEqualTo("FAILURE");
    assertThat(failed.after()).containsEntry("code", "MW-CORE-INSUFFICIENT_BALANCE");
    assertThat(failed.payload().get(1).body()).doesNotContain("Rahim").doesNotContain("01711000001");
    assertThat(ContractSchemas.schema(AUDIT).validate(published(failed))).isEmpty();

    AuditEvent unknown = new AuditMapper().coreCall(validate, CoreReply.ambiguous(validate, 0), TestData.NOW, 3);
    assertThat(unknown.outcome()).isEqualTo("FAILURE");
    assertThat(unknown.payload()).hasSize(1);
    assertThat(ContractSchemas.schema(AUDIT).validate(published(unknown))).isEmpty();
  }

  @Test
  void aBalanceAnswerNeverReachesTheAuditTrail() {
    CoreCommand balance = new CoreCommandMapper().balanceInquiry(rec, TestData.NOW, Duration.ofSeconds(60));
    CoreReply reply = new CoreReply("BalanceInquiry", balance.commandId(), balance.txnId(), CoreReply.Kind.ANSWERED,
        200, null, JSON.readTree("{\"available\":{\"valueMinor\":987654,\"currency\":\"BDT\"}}"));

    AuditEvent e = new AuditMapper().coreCall(balance, reply, TestData.NOW, 5);

    assertThat(e.payload()).extracting(AuditEvent.Payload::direction).containsExactly("OUT");
    assertThat(JSON.writeValueAsString(e)).doesNotContain("987654");
  }

  @Test
  void theOtherAuditEventsStayWithoutHopFacts() {
    JsonNode body = published(new AuditMapper().stateChanged(rec, null, TestData.NOW));

    assertThat(body.has("hop")).isFalse();
    assertThat(body.has("payload")).isFalse();
    assertThat(body.has("durationMs")).isFalse();
  }
}
