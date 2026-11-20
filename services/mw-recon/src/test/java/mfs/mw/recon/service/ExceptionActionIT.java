package mfs.mw.recon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.networknt.schema.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import mfs.mw.recon.TestInfrastructure;
import mfs.mw.recon.contracts.ContractSchemas;
import mfs.mw.recon.dto.ExceptionView;
import mfs.mw.recon.dto.TxnStatusEvent;
import mfs.mw.recon.exception.ErrorCodes;
import mfs.mw.recon.exception.MwException;
import mfs.mw.recon.repository.ReconStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Ticket 127b: an approved action (taken by mw-admin-api) moves an exception to ACTIONED, the transaction's outcome on
 * mw.txn.status.v1 moves it to RESOLVED, and every state change goes out on mw.recon.exception.v1.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestInfrastructure.class)
class ExceptionActionIT {

  private final ReconService recon;
  private final ExceptionQueueService queue;
  private final ReconStore store;
  private final JdbcTemplate jdbc;
  private final JsonMapper json;

  @Autowired
  ExceptionActionIT(ReconService recon, ExceptionQueueService queue, ReconStore store, JdbcTemplate jdbc,
      JsonMapper json) {
    this.recon = recon;
    this.queue = queue;
    this.store = store;
    this.jdbc = jdbc;
    this.json = json;
  }

  private UUID exceptionOf(UUID txnId) {
    UUID id = UUID.randomUUID();
    store.insertStandaloneException(id, "STATUS_MISMATCH", "LEDGER_MISSING", "ops", "billagg", null, 5_000L,
        Instant.parse("2026-11-19T10:00:00Z"));
    jdbc.update("update recon_exception set txn_id = ? where id = ?", txnId, id);
    return id;
  }

  private TxnStatusEvent status(UUID txnId, String status, Instant at) {
    return new TxnStatusEvent(txnId.toString(), "BILL_PAY", status, new TxnStatusEvent.Partner("billagg"), "B-1",
        null, new TxnStatusEvent.Amount(5_000L, "BDT"), at, "COMPLETED".equals(status) ? at : null);
  }

  private List<JsonNode> events(UUID exceptionId) {
    return jdbc.queryForList("select payload::text from outbox where topic = ? and msg_key = ? order by id",
        String.class, ReconTopics.EXCEPTION, exceptionId.toString()).stream().map(json::readTree).toList();
  }

  @Test
  void anApprovedActionIsActionedThenResolvedByTheOutcome() {
    UUID txnId = UUID.randomUUID();
    UUID id = exceptionOf(txnId);
    recon.onTxn(status(txnId, "MANUAL_REVIEW", Instant.parse("2026-11-19T10:00:00Z")), "e1");
    UUID cr = UUID.randomUUID();

    ExceptionView actioned = queue.action(id, "FORCE_COMMIT", cr, "seed-recon-checker");
    assertThat(actioned.state()).isEqualTo("ACTIONED");
    assertThat(actioned.action()).isEqualTo("FORCE_COMMIT");
    assertThat(actioned.changeRequestId()).isEqualTo(cr.toString());
    assertThat(queue.action(id, "FORCE_COMMIT", cr, "seed-recon-checker").state()).isEqualTo("ACTIONED");

    recon.onTxn(status(txnId, "FAILED", Instant.parse("2026-11-19T10:01:00Z")), "e2");
    assertThat(queue.get(id).state()).isEqualTo("ACTIONED");
    recon.onTxn(status(txnId, "COMPLETED", Instant.parse("2026-11-19T10:02:00Z")), "e3");
    assertThat(queue.get(id).state()).isEqualTo("RESOLVED");

    List<JsonNode> events = events(id);
    assertThat(events).extracting(e -> e.path("state").asString()).containsExactly("ACTIONED", "RESOLVED");
    Schema schema = ContractSchemas.schema("events/mw-recon/mw.recon.exception.v1.schema.json");
    for (JsonNode e : events) {
      assertThat(schema.validate(e)).isEmpty();
      assertThat(e.path("changeRequestId").asString()).isEqualTo(cr.toString());
      assertThat(e.path("txnId").asString()).isEqualTo(txnId.toString());
    }
    assertThat(events.get(0).path("actor").asString()).isEqualTo("seed-recon-checker");

    assertThatThrownBy(() -> queue.action(id, "REVERSE", UUID.randomUUID(), "seed-recon-checker"))
        .isInstanceOfSatisfying(MwException.class, e -> assertThat(e.code()).isEqualTo(ErrorCodes.VAL_STATE_CONFLICT));
  }

  @Test
  void anOutcomeAlreadySeenResolvesAtOnceAndExplanationsAreEvents() {
    UUID txnId = UUID.randomUUID();
    UUID id = exceptionOf(txnId);
    queue.explain(id, "checking with the biller", "seed-recon-maker");
    recon.onTxn(status(txnId, "REVERSED", Instant.parse("2026-11-19T11:00:00Z")), "r1");

    assertThat(queue.action(id, "REVERSE", UUID.randomUUID(), "seed-recon-checker").state()).isEqualTo("RESOLVED");
    assertThat(events(id)).extracting(e -> e.path("state").asString())
        .containsExactly("EXPLAINED", "ACTIONED", "RESOLVED");
  }

  @Test
  void anExceptionWithoutATransactionCannotBeActioned() {
    UUID id = UUID.randomUUID();
    store.insertStandaloneException(id, "AMOUNT_MISMATCH", "TRUST_POSITION", "finance", "trustbank", 1L, 2L,
        Instant.parse("2026-11-19T10:00:00Z"));
    assertThatThrownBy(() -> queue.action(id, "FORCE_COMMIT", UUID.randomUUID(), "seed-recon-checker"))
        .isInstanceOfSatisfying(MwException.class, e -> assertThat(e.code()).isEqualTo(ErrorCodes.VAL_STATE_CONFLICT));
    assertThat(queue.get(id).state()).isEqualTo("OPEN");
  }
}
