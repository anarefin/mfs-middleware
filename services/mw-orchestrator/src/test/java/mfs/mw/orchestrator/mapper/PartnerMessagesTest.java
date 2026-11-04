package mfs.mw.orchestrator.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import mfs.mw.orchestrator.TestData;
import mfs.mw.orchestrator.contracts.ContractSchemas;
import mfs.mw.orchestrator.dto.CoreCommand;
import mfs.mw.orchestrator.dto.CoreEvent;
import mfs.mw.orchestrator.dto.ErrorInfo;
import mfs.mw.orchestrator.dto.PartnerReply;
import mfs.mw.orchestrator.dto.PartnerRequest;
import mfs.mw.orchestrator.entity.TransactionRecord;
import mfs.mw.orchestrator.service.saga.PartnerCall;
import mfs.mw.orchestrator.service.saga.SagaEvent;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The hold-path messages this service builds and reads, checked against their contracts. */
class PartnerMessagesTest {

  private static final String COMMANDS = "events/mw-orchestrator/core.command.v1.schema.json";
  private static final String REQUESTS = "events/mw-orchestrator/mw.partner.request.v1.schema.json";

  private final JsonMapper json = JsonMapper.builder().build();
  private final TransactionRecord rec =
      new RequestMapper(json)
          .toRecord(TestData.topup(TestData.txnId(), "k-1", 2_000, "01711000002"), null, TestData.NOW)
          .withHold("HOLD-1");

  @Test
  void holdCommitReleaseAndQueryAreValidCommands() {
    CoreCommandMapper m = new CoreCommandMapper();
    Duration ttl = Duration.ofSeconds(60);
    for (CoreCommand c :
        new CoreCommand[] {m.hold(rec, TestData.NOW, ttl), m.commit(rec, TestData.NOW, ttl), m.release(rec, TestData.NOW, ttl), m.query(rec, TestData.NOW, ttl)}) {
      assertThat(ContractSchemas.schema(COMMANDS).validate(json.valueToTree(c))).as(c.commandType()).isEmpty();
    }
    assertThat(m.commit(rec, TestData.NOW, ttl).commandId()).isEqualTo(rec.txnId() + ":commit");
    assertThat(m.query(rec, TestData.NOW, ttl).commandId())
        .isEqualTo(rec.txnId() + ":query:" + TestData.NOW.toEpochMilli());
  }

  @Test
  void aPayRequestIsAValidPartnerRequestWithoutThePin() {
    PartnerRequest r =
        new PartnerRequestMapper().pay(rec, new PartnerCall("telco", "mw.partner.request.telco.v1"), TestData.NOW, Duration.ofSeconds(30));

    assertThat(ContractSchemas.schema(REQUESTS).validate(json.valueToTree(r))).isEmpty();
    assertThat(r.requestId()).isEqualTo(PartnerRequestMapper.requestId(rec.txnId().toString(), "PAY"));
    assertThat(json.writeValueAsString(r)).doesNotContain("ciphertext");
  }

  @Test
  void everyValidTelcoReplySampleReadsIntoTheOwnType() throws Exception {
    PartnerReplyMapper mapper = new PartnerReplyMapper();
    int read = 0;
    for (Path p : ContractSchemas.samples("events/mw-orchestrator/samples/mw.partner.reply.v1/telco", "valid")) {
      PartnerReply reply = json.readValue(Files.readString(p), PartnerReply.class);
      assertThat(reply.requestId()).isNotBlank();
      if ("PAY".equals(reply.payload().operation())) {
        assertThat(mapper.toSagaEvent(reply)).as(p.toString()).isPresent();
      } else {
        // STATUS_QUERY answers (including NOT_FOUND) are read by InquiryService, not as PAY events
        assertThat(reply.payload().operation()).as(p.toString()).isEqualTo("STATUS_QUERY");
      }
      read++;
    }
    assertThat(read).isPositive();
  }

  @Test
  void replyErrorsKeepContractCodesAndReplaceOthers() {
    PartnerReplyMapper mapper = new PartnerReplyMapper();
    ErrorInfo kept = mapper.error(reply("FAILED", "MW-SYS-PARTNER_UNAVAILABLE", true, "gp"));
    assertThat(kept.code()).isEqualTo("MW-SYS-PARTNER_UNAVAILABLE");
    assertThat(kept.retryable()).isTrue();
    ErrorInfo replaced = mapper.error(reply("FAILED", null, null, "robi-ocs"));
    assertThat(replaced.code()).isEqualTo("MW-PTR-ROBI_OCS-UNMAPPED");
    assertThat(replaced.message()).isEqualTo(PartnerReplyMapper.FALLBACK_MESSAGE);
    assertThat(replaced.retryable()).isFalse();
    assertThat(mapper.error(reply("FAILED", null, null, null)).code()).isEqualTo("MW-PTR-UNKNOWN-UNMAPPED");
    assertThat(mapper.toSagaEvent(reply("PENDING", null, null, "gp"))).isEmpty();
    assertThat(mapper.toSagaEvent(reply("UNKNOWN", null, null, "gp"))).containsInstanceOf(SagaEvent.PartnerUnknown.class);
  }

  @Test
  void aTransactionStatusAnswerIsAHoldQueryResult() {
    CoreEventMapper mapper = new CoreEventMapper();
    CoreEvent held = coreEvent("{\"txnStatus\":\"HELD\",\"holdId\":\"HOLD-9\"}");
    CoreEvent none = coreEvent("{\"txnStatus\":\"NOT_FOUND\"}");

    assertThat(mapper.toSagaEvent(held, true)).contains(new SagaEvent.HoldQueryResult(true));
    assertThat(mapper.holdId(held)).isEqualTo("HOLD-9");
    assertThat(mapper.toSagaEvent(none, true)).contains(new SagaEvent.HoldQueryResult(false));
    assertThat(mapper.holdId(none)).isNull();
  }

  private PartnerReply reply(String outcome, String code, Boolean retryable, String partnerId) {
    return new PartnerReply(
        1, "telco", "r-1", TestData.txnId(), "c-1", partnerId, TestData.NOW,
        new PartnerReply.Payload("PAY", outcome, null, null, code, null, retryable, null));
  }

  private CoreEvent coreEvent(String payload) {
    return json.readValue(
        "{\"schemaVersion\":1,\"eventType\":\"TransactionStatus\",\"eventId\":\"e\",\"commandId\":\"c\","
            + "\"txnId\":\"t\",\"coreRef\":\"r\",\"outcome\":\"SUCCESS\",\"payload\":" + payload + "}",
        CoreEvent.class);
  }
}
