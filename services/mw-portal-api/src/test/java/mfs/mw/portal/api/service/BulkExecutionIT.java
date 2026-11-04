package mfs.mw.portal.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import mfs.mw.portal.api.MutableClock;
import mfs.mw.portal.api.TestInfrastructure;
import mfs.mw.portal.api.contracts.ContractSchemas;
import mfs.mw.portal.api.dto.BatchView;
import mfs.mw.portal.api.dto.PortalUser;
import mfs.mw.portal.api.dto.StatusMessage;
import mfs.mw.portal.api.exception.MwException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Execution of an approved bulk batch (spec 9.4, WP22, ticket 34) through its service seams, against
 * PostgreSQL: the dispatcher streams one {@code mw.txn.request.v1} child per row into the outbox at
 * the configured rate (measured here on a test clock), with a txnId and idempotency key derived from
 * the batch and the row, so a re-run after a crash sends the very same request and the orchestrator
 * pays it once; pause stops the stream, resume continues it; the status records of the children
 * settle each row, complete the batch and fill the result file.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "mw.outbox.relay.enabled=false",
      "mw.portal-api.consumers.enabled=false",
      "mw.portal-api.bulk.dispatch.enabled=false",
      "mw.portal-api.bulk.dispatch.rate-per-second=20",
      "mw.portal-api.bulk.dispatch.period=100ms"
    })
@Import({TestInfrastructure.class, BulkExecutionIT.TestClock.class})
class BulkExecutionIT {

  static final Instant START = Instant.parse("2026-11-04T10:00:00Z");
  static final String REQUEST_SCHEMA = "events/mw-orchestrator/mw.txn.request.v1.schema.json";

  @TestConfiguration(proxyBeanMethods = false)
  static class TestClock {
    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock(START);
    }
  }

  static final PortalUser MAKER = new PortalUser("maker-1", "maker-1-name", "ORG001", Set.of("org-maker"));
  static final PortalUser CHECKER = new PortalUser("checker-1", "checker-1-name", "ORG001", Set.of("org-checker"));
  static final PortalUser NO_WALLET_MAKER = new PortalUser("m-9", "m-9", "ORG999", Set.of("org-maker"));
  static final PortalUser NO_WALLET_CHECKER = new PortalUser("c-9", "c-9", "ORG999", Set.of("org-checker"));

  private final BatchService batches;
  private final BulkExecutionService execution;
  private final MutableClock clock;
  private final JdbcTemplate jdbc;
  private final JsonMapper json;

  @Autowired
  BulkExecutionIT(
      BatchService batches, BulkExecutionService execution, MutableClock clock, JdbcTemplate jdbc, JsonMapper json) {
    this.batches = batches;
    this.execution = execution;
    this.clock = clock;
    this.jdbc = jdbc;
    this.json = json;
  }

  @BeforeEach
  void clean() {
    clock.set(START);
    jdbc.update("delete from outbox");
    jdbc.update("delete from batch_item");
    jdbc.update("delete from batch");
    execution.resetThrottle();
  }

  // --- helpers -------------------------------------------------------------------------------

  static String rows(int n) {
    StringBuilder b = new StringBuilder("msisdn,amount,reference\n");
    for (int i = 1; i <= n; i++) {
      b.append("0171").append(String.format("%07d", i)).append(",1.25,SAL-").append(i).append('\n');
    }
    return b.toString();
  }

  private String approved(int n) {
    BatchView v = batches.upload(MAKER, rows(n).getBytes(StandardCharsets.UTF_8));
    batches.approve(CHECKER, v.batchId());
    return v.batchId();
  }

  private List<JsonNode> requests() {
    return jdbc.queryForList(
            "select payload::text from outbox where topic = 'mw.txn.request.v1' order by id", String.class)
        .stream()
        .map(json::readTree)
        .toList();
  }

  /** Dispatches every {@code tick} for {@code total}, returning how many children each tick sent. */
  private List<Integer> run(Duration total, Duration tick) {
    List<Integer> sent = new ArrayList<>();
    for (Duration t = Duration.ZERO; t.compareTo(total) < 0; t = t.plus(tick)) {
      clock.advance(tick);
      sent.add(execution.dispatch());
    }
    return sent;
  }

  private void drain(String batchId) {
    for (int i = 0; i < 10_000 && execution.dispatch() >= 0 && pending(batchId) > 0; i++) {
      clock.advance(Duration.ofSeconds(1));
    }
  }

  private int pending(String batchId) {
    return jdbc.queryForObject(
        "select count(*) from batch_item where batch_id = ?::uuid and status = 'PENDING'", Integer.class, batchId);
  }

  private StatusMessage status(JsonNode req, String status, String errorCode) {
    String error =
        errorCode == null ? "" : ",\"error\":{\"code\":\"" + errorCode + "\",\"message\":\"x\",\"retryable\":false}";
    return json.readValue(
        "{\"schemaVersion\":1,\"txnId\":\"" + req.path("txnId").asString() + "\",\"correlationId\":\"c\","
            + "\"channel\":\"ORG_PORTAL\",\"txnType\":\"BULK_DISBURSE\",\"status\":\"" + status + "\","
            + "\"amount\":{\"valueMinor\":125,\"currency\":\"BDT\"},\"coreRef\":\"CORE-1\","
            + "\"requestedAt\":\"2026-11-04T10:00:00Z\",\"updatedAt\":\"2026-11-04T10:00:01Z\"" + error + "}",
        StatusMessage.class);
  }

  // --- children ------------------------------------------------------------------------------

  @Test
  void approvalGivesTheBatchAParentTxnAndEveryRowBecomesAValidChildRequest() {
    String id = approved(3);
    BatchView v = batches.get(CHECKER, id);
    assertThat(v.status()).isEqualTo("APPROVED");
    assertThat(v.parentTxnId()).matches("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");

    drain(id);

    List<JsonNode> reqs = requests();
    assertThat(reqs).hasSize(3);
    for (JsonNode r : reqs) {
      assertThat(ContractSchemas.schema(REQUEST_SCHEMA).validate(r)).as(r.toString()).isEmpty();
      assertThat(r.path("channel").asString()).isEqualTo("ORG_PORTAL");
      assertThat(r.path("txnType").asString()).isEqualTo("BULK_DISBURSE");
      assertThat(r.path("initiator").path("id").asString()).isEqualTo("ORG001");
      assertThat(r.path("debitParty").path("type").asString()).isEqualTo("ORG");
      assertThat(r.path("debitParty").path("id").asString()).isEqualTo("01311000001");
      assertThat(r.path("creditParty").path("walletType").asString()).isEqualTo("CUSTOMER");
      assertThat(r.path("amount").path("valueMinor").asLong()).isEqualTo(125);
      assertThat(r.path("attributes").path("parentTxnId").asString()).isEqualTo(v.parentTxnId());
      assertThat(r.has("encryptedPin")).isFalse();
    }
    assertThat(reqs.get(0).path("creditParty").path("id").asString()).isEqualTo("01710000001");
    assertThat(reqs.get(0).path("attributes").path("reference").asString()).isEqualTo("SAL-1");
    assertThat(reqs.stream().map(r -> r.path("txnId").asString()).distinct()).hasSize(3);
    assertThat(batches.get(CHECKER, id).status()).isEqualTo("EXECUTING");
  }

  @Test
  void anOrgWithoutAPayoutWalletCannotHaveABatchApproved() {
    BatchView v = batches.upload(NO_WALLET_MAKER, rows(1).getBytes(StandardCharsets.UTF_8));
    assertThatThrownBy(() -> batches.approve(NO_WALLET_CHECKER, v.batchId()))
        .isInstanceOfSatisfying(MwException.class, e -> assertThat(e.code()).isEqualTo("MW-VAL-034"));
    assertThat(batches.get(NO_WALLET_CHECKER, v.batchId()).status()).isEqualTo("PENDING_APPROVAL");
  }

  // --- throttle ------------------------------------------------------------------------------

  @Test
  void theThrottleRateIsHonoured() {
    approved(1_000);
    List<Integer> perTick = run(Duration.ofSeconds(10), Duration.ofMillis(100));

    int total = perTick.stream().mapToInt(Integer::intValue).sum();
    assertThat(total).as("20 rows/s for 10 s").isBetween(190, 200);
    // no one-second window (10 ticks) carries more than the rate
    for (int i = 0; i + 10 <= perTick.size(); i++) {
      int window = perTick.subList(i, i + 10).stream().mapToInt(Integer::intValue).sum();
      assertThat(window).as("window from tick %d", i).isLessThanOrEqualTo(20);
    }
    assertThat(requests()).hasSize(total);
  }

  @Test
  void anIdleDispatcherDoesNotBuildUpABurst() {
    clock.advance(Duration.ofMinutes(10)); // nothing to send for a long time
    execution.dispatch();
    approved(100);
    List<Integer> perTick = run(Duration.ofSeconds(1), Duration.ofMillis(100));
    assertThat(perTick.stream().mapToInt(Integer::intValue).sum()).isLessThanOrEqualTo(22);
  }

  // --- crash and re-run ----------------------------------------------------------------------

  @Test
  void aRowResentAfterACrashIsTheSameRequestSoItIsPaidOnce() {
    String id = approved(5);
    drain(id);
    List<JsonNode> first = requests();

    // a crash lost what the portal knew about the sends (rows back to PENDING, nothing settled)
    jdbc.update("update batch_item set status = 'PENDING', txn_id = null where batch_id = ?::uuid", id);
    jdbc.update("delete from outbox");
    drain(id);
    List<JsonNode> again = requests();

    assertThat(again).hasSize(5);
    for (int i = 0; i < 5; i++) {
      JsonNode a = first.get(i);
      JsonNode b = again.get(i);
      assertThat(b.path("txnId").asString()).isEqualTo(a.path("txnId").asString());
      assertThat(b.path("idempotencyKey").asString()).isEqualTo(a.path("idempotencyKey").asString());
      // the business content the orchestrator hashes for its idempotency check
      for (String f : List.of("channel", "txnType", "initiator", "debitParty", "creditParty", "amount", "attributes")) {
        assertThat(b.path(f)).as(f).isEqualTo(a.path(f));
      }
    }
  }

  @Test
  void aRowAlreadySentIsNeverSentAgain() {
    String id = approved(4);
    drain(id);
    clock.advance(Duration.ofSeconds(30));
    drain(id);
    run(Duration.ofSeconds(3), Duration.ofMillis(100));
    assertThat(requests()).hasSize(4);
    Set<String> keys = new HashSet<>();
    requests().forEach(r -> keys.add(r.path("idempotencyKey").asString()));
    assertThat(keys).hasSize(4);
  }

  // --- pause / resume ------------------------------------------------------------------------

  @Test
  void aPausedBatchSendsNothingUntilResumed() {
    String id = approved(100);
    run(Duration.ofSeconds(1), Duration.ofMillis(100));
    int before = requests().size();
    assertThat(before).isPositive();

    assertThat(batches.pause(CHECKER, id).status()).isEqualTo("PAUSED");
    run(Duration.ofSeconds(2), Duration.ofMillis(100));
    assertThat(requests()).hasSize(before);
    assertThatThrownBy(() -> batches.pause(CHECKER, id))
        .isInstanceOfSatisfying(MwException.class, e -> assertThat(e.code()).isEqualTo("MW-VAL-033"));

    assertThat(batches.resume(CHECKER, id).status()).isEqualTo("EXECUTING");
    drain(id);
    assertThat(requests()).hasSize(100);
    assertThat(requests().stream().map(r -> r.path("txnId").asString()).distinct()).hasSize(100);
  }

  @Test
  void aBatchAwaitingApprovalCannotBePausedOrResumed() {
    BatchView v = batches.upload(MAKER, rows(1).getBytes(StandardCharsets.UTF_8));
    assertThatThrownBy(() -> batches.pause(CHECKER, v.batchId()))
        .isInstanceOfSatisfying(MwException.class, e -> assertThat(e.code()).isEqualTo("MW-VAL-033"));
    assertThatThrownBy(() -> batches.resume(CHECKER, v.batchId()))
        .isInstanceOfSatisfying(MwException.class, e -> assertThat(e.code()).isEqualTo("MW-VAL-033"));
  }

  // --- outcomes and the result file ----------------------------------------------------------

  @Test
  void childOutcomesCompleteTheBatchAndFillTheResultFile() {
    String id = approved(3);
    drain(id);
    List<JsonNode> reqs = requests();

    execution.apply(status(reqs.get(0), "VALIDATING", null)); // in progress: nothing to record
    execution.apply(status(reqs.get(0), "COMPLETED", null));
    execution.apply(status(reqs.get(1), "REJECTED", "MW-CORE-INSUFFICIENT_BALANCE"));
    assertThat(batches.get(CHECKER, id).status()).isEqualTo("EXECUTING");
    execution.apply(status(reqs.get(2), "COMPLETED", null));
    execution.apply(status(reqs.get(2), "COMPLETED", null)); // redelivered

    BatchView done = batches.get(CHECKER, id);
    assertThat(done.status()).isEqualTo("COMPLETED");
    assertThat(done.progress().completed()).isEqualTo(2);
    assertThat(done.progress().failed()).isEqualTo(1);
    assertThat(done.progress().pending()).isZero();
    assertThat(done.progress().paidAmount().valueMinor()).isEqualTo(250);

    String csv = batches.resultFile(MAKER, id);
    assertThat(csv.lines().toList())
        .containsExactly(
            "line,msisdn,amount,reference,status,txnId,errorCode",
            "2,0171****001,1.25,SAL-1,COMPLETED," + reqs.get(0).path("txnId").asString() + ",",
            "3,0171****002,1.25,SAL-2,REJECTED," + reqs.get(1).path("txnId").asString() + ",MW-CORE-INSUFFICIENT_BALANCE",
            "4,0171****003,1.25,SAL-3,COMPLETED," + reqs.get(2).path("txnId").asString() + ",");
  }

  @Test
  void aSettledRowIsNotOverwrittenByAnOlderOrConflictingRecord() {
    String id = approved(1);
    drain(id);
    JsonNode req = requests().get(0);
    execution.apply(status(req, "FAILED", "MW-TMO-001"));
    execution.apply(status(req, "COMPLETED", null));
    assertThat(batches.resultFile(MAKER, id)).contains(",FAILED,");
  }

  @Test
  void statusRecordsOfOtherChannelsOrUnknownTxnsAreIgnored() {
    String id = approved(1);
    drain(id);
    JsonNode req = requests().get(0);
    StatusMessage s = status(req, "COMPLETED", null);
    execution.apply(
        new StatusMessage(s.txnId(), "CUSTOMER_APP", "P2P", "COMPLETED", null, null, null, s.updatedAt()));
    execution.apply(
        new StatusMessage(
            "01933b8e-0000-7000-8000-00000000abcd", "ORG_PORTAL", "BULK_DISBURSE", "COMPLETED", null, null, null,
            s.updatedAt()));
    assertThat(batches.get(CHECKER, id).status()).isEqualTo("EXECUTING");
  }

  @Test
  void theResultFileOfABatchNotApprovedIsRefused() {
    BatchView v = batches.upload(MAKER, rows(1).getBytes(StandardCharsets.UTF_8));
    assertThatThrownBy(() -> batches.resultFile(MAKER, v.batchId()))
        .isInstanceOfSatisfying(MwException.class, e -> assertThat(e.code()).isEqualTo("MW-VAL-035"));
  }
}
