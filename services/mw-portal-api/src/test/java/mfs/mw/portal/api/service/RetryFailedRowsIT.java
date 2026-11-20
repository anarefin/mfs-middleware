package mfs.mw.portal.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import mfs.mw.portal.api.MutableClock;
import mfs.mw.portal.api.TestInfrastructure;
import mfs.mw.portal.api.contracts.ContractSchemas;
import mfs.mw.portal.api.dto.BatchView;
import mfs.mw.portal.api.dto.PortalUser;
import mfs.mw.portal.api.dto.StatusMessage;
import mfs.mw.portal.api.exception.MwException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Retry of a settled batch's FAILED rows (ticket 129c, FRS CH-POR-03) through its service seams,
 * against PostgreSQL and a stub of mw-orchestrator's query API. A row never pays twice: the
 * orchestrator answers a re-sent key that ended FAILED with that FAILED, so a retried row is sent
 * as a new attempt ({@code bulk-<batchId>-<line>-a<n>}), and only after the failed attempt is
 * proven to have posted nothing (still FAILED, never UNKNOWN, no core reference, every PostTransfer
 * refused). The retry is one maker-checker decision; a repeated click mints no second attempt.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "mw.outbox.relay.enabled=false",
      "mw.portal-api.consumers.enabled=false",
      "mw.portal-api.bulk.dispatch.enabled=false",
      "mw.portal-api.bulk.dispatch.rate-per-second=100",
      "mw.portal-api.bulk.dispatch.period=1s",
      "mw.portal-api.bulk.reconcile.enabled=false",
      "mw.portal-api.bulk.expiry.enabled=false",
      "mw.portal-api.bulk.retry.enabled=false"
    })
@Import({TestInfrastructure.class, RetryFailedRowsIT.TestClock.class})
class RetryFailedRowsIT {

  static final Instant START = Instant.parse("2026-11-04T10:00:00Z");
  static final String REQUEST_SCHEMA = "events/mw-orchestrator/mw.txn.request.v1.schema.json";
  static final String TOKEN = "retry-token";

  @TestConfiguration(proxyBeanMethods = false)
  static class TestClock {
    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock(START);
    }
  }

  // --- orchestrator stub: txnId -> the TxnDetail JSON it answers, absent 404 ---------------------

  static final Map<String, String> DETAILS = new ConcurrentHashMap<>();
  static volatile boolean orchestratorDown;
  static final HttpServer ORCHESTRATOR = startStub();

  static HttpServer startStub() {
    try {
      HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      s.createContext("/internal/v1/transactions/", RetryFailedRowsIT::answer);
      s.createContext("/token", ex -> respond(ex, 200, "{\"access_token\":\"" + TOKEN + "\",\"expires_in\":300}"));
      s.start();
      return s;
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  static void answer(HttpExchange ex) throws IOException {
    String path = ex.getRequestURI().getPath();
    String txnId = path.substring(path.lastIndexOf('/') + 1);
    String detail = DETAILS.get(txnId);
    if (!("Bearer " + TOKEN).equals(ex.getRequestHeaders().getFirst("Authorization"))) {
      respond(ex, 401, "{}");
    } else if (orchestratorDown) {
      respond(ex, 500, "{}");
    } else if (detail == null || !String.valueOf(ex.getRequestURI().getRawQuery()).contains("orgId=ORG001")) {
      respond(ex, 404, "{\"code\":\"MW-VAL-003\",\"message\":\"x\",\"correlationId\":\"c\",\"retryable\":false,\"details\":[]}");
    } else {
      respond(ex, 200, detail);
    }
  }

  static void respond(HttpExchange ex, int status, String body) throws IOException {
    byte[] b = body.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().add("Content-Type", "application/json");
    ex.sendResponseHeaders(status, b.length);
    ex.getResponseBody().write(b);
    ex.close();
  }

  @AfterAll
  static void stopStub() {
    ORCHESTRATOR.stop(0);
  }

  @DynamicPropertySource
  static void orchestrator(DynamicPropertyRegistry registry) {
    String base = "http://127.0.0.1:" + ORCHESTRATOR.getAddress().getPort();
    registry.add("mw.portal-api.orchestrator.base-url", () -> base);
    registry.add("mw.portal-api.orchestrator.auth.token-uri", () -> base + "/token");
  }

  /** A FAILED child the core refused at validation: nothing was posted. */
  static String refusedAtValidation(String txnId) {
    return detail(txnId, "FAILED", null,
        "{\"kind\":\"STATE\",\"from\":\"RECEIVED\",\"to\":\"VALIDATING\",\"at\":\"2026-11-04T10:00:00Z\"},"
            + core("ValidateTransaction", "FAILURE") + ","
            + "{\"kind\":\"STATE\",\"from\":\"VALIDATING\",\"to\":\"FAILED\",\"at\":\"2026-11-04T10:00:01Z\"}");
  }

  /** A FAILED child whose PostTransfer the core refused (422): nothing was posted. */
  static String postingRefused(String txnId) {
    return detail(txnId, "FAILED", null,
        core("ValidateTransaction", "SUCCESS") + "," + core("PostTransfer", "FAILURE") + ","
            + "{\"kind\":\"STATE\",\"from\":\"POSTING\",\"to\":\"FAILED\",\"at\":\"2026-11-04T10:00:01Z\"}");
  }

  static String core(String command, String outcome) {
    return "{\"kind\":\"CORE\",\"command\":\"" + command + "\",\"commandId\":\"x\",\"sends\":1,"
        + (outcome == null ? "" : "\"result\":\"R\",\"outcome\":\"" + outcome + "\",")
        + "\"at\":\"2026-11-04T10:00:00Z\"}";
  }

  static String detail(String txnId, String status, String coreRef, String timeline) {
    return "{\"transaction\":{\"txnId\":\"" + txnId + "\",\"txnType\":\"BULK_DISBURSE\",\"channel\":\"ORG_PORTAL\","
        + "\"status\":\"" + status + "\"," + (coreRef == null ? "" : "\"coreRef\":\"" + coreRef + "\",")
        + "\"amount\":{\"valueMinor\":125,\"currency\":\"BDT\"},"
        + "\"error\":{\"code\":\"MW-CORE-LIMIT_EXCEEDED_DAILY\",\"message\":\"x\",\"retryable\":false},"
        + "\"createdAt\":\"2026-11-04T10:00:00Z\",\"updatedAt\":\"2026-11-04T10:00:01Z\"},"
        + "\"timeline\":[" + timeline + "],\"partnerCalls\":[]}";
  }

  static final PortalUser MAKER = new PortalUser("maker-sub-1", "org-maker01", "ORG001", Set.of("org-maker"));
  static final PortalUser MAKER_2 = new PortalUser("maker-sub-2", "org-maker02", "ORG001", Set.of("org-maker"));
  static final PortalUser CHECKER = new PortalUser("checker-sub-1", "org-checker01", "ORG001", Set.of("org-checker"));
  static final PortalUser BOTH =
      new PortalUser("both-sub", "org-both", "ORG001", Set.of("org-maker", "org-checker"));

  private final BatchService batches;
  private final BulkExecutionService execution;
  private final BulkRetryService retry;
  private final MutableClock clock;
  private final JdbcTemplate jdbc;
  private final JsonMapper json;

  @Autowired
  RetryFailedRowsIT(
      BatchService batches, BulkExecutionService execution, BulkRetryService retry, MutableClock clock,
      JdbcTemplate jdbc, JsonMapper json) {
    this.batches = batches;
    this.execution = execution;
    this.retry = retry;
    this.clock = clock;
    this.jdbc = jdbc;
    this.json = json;
  }

  @BeforeEach
  void clean() {
    clock.set(START);
    DETAILS.clear();
    orchestratorDown = false;
    jdbc.update("delete from outbox");
    jdbc.update("delete from batch_item");
    jdbc.update("delete from batch");
    execution.resetThrottle();
  }

  // --- helpers -------------------------------------------------------------------------------

  static byte[] rows(int n) {
    StringBuilder b = new StringBuilder("msisdn,amount,reference\n");
    for (int i = 1; i <= n; i++) {
      b.append("0171").append(String.format("%07d", i)).append(",1.25,SAL-").append(i).append('\n');
    }
    return b.toString().getBytes(StandardCharsets.UTF_8);
  }

  /** A batch of n rows, approved and every row sent; returns its id. */
  private String sent(int n) {
    String id = batches.upload(MAKER, rows(n)).batchId();
    batches.submit(MAKER, id);
    batches.approve(CHECKER, id);
    drain();
    return id;
  }

  private void drain() {
    for (int i = 0; i < 100 && execution.dispatch() >= 0; i++) {
      clock.advance(Duration.ofSeconds(1));
      if (jdbc.queryForObject("select count(*) from batch_item where status = 'PENDING'", Integer.class) == 0) {
        return;
      }
    }
  }

  private List<JsonNode> requests() {
    return jdbc.queryForList(
            "select payload::text from outbox where topic = 'mw.txn.request.v1' order by id", String.class)
        .stream()
        .map(json::readTree)
        .toList();
  }

  private void settle(String txnId, String status) {
    execution.apply(json.readValue(
        "{\"schemaVersion\":1,\"txnId\":\"" + txnId + "\",\"correlationId\":\"c\",\"channel\":\"ORG_PORTAL\","
            + "\"txnType\":\"BULK_DISBURSE\",\"status\":\"" + status + "\","
            + "\"amount\":{\"valueMinor\":125,\"currency\":\"BDT\"},"
            + "\"requestedAt\":\"2026-11-04T10:00:00Z\",\"updatedAt\":\"2026-11-04T10:00:01Z\"}",
        StatusMessage.class));
  }

  private List<String> rowStatuses(String batchId) {
    return jdbc.queryForList(
        "select status from batch_item where batch_id = ?::uuid order by line_no", String.class, batchId);
  }

  private static String code(Runnable r) {
    try {
      r.run();
    } catch (MwException e) {
      return e.code();
    }
    return null;
  }

  // --- tests ---------------------------------------------------------------------------------

  @Test
  void onlyTheFailedRowsAreSentAgainAsANewAttemptOnceTheRetryIsApproved() {
    String id = sent(4);
    List<JsonNode> first = requests();
    String completed = first.get(0).path("txnId").asString();
    String failed = first.get(1).path("txnId").asString();
    String rejected = first.get(2).path("txnId").asString();
    String failedToo = first.get(3).path("txnId").asString();
    settle(completed, "COMPLETED");
    settle(failed, "FAILED");
    settle(rejected, "REJECTED");
    settle(failedToo, "FAILED");
    assertThat(batches.get(CHECKER, id).status()).isEqualTo("COMPLETED");
    DETAILS.put(failed, refusedAtValidation(failed));
    DETAILS.put(failedToo, postingRefused(failedToo));

    BatchView requested = batches.requestRetry(MAKER, id);
    assertThat(requested.retryRequestedBy()).isEqualTo("org-maker01");
    assertThat(requests()).hasSize(4); // a request alone sends nothing

    BatchView approved = batches.approveRetry(CHECKER, id);
    assertThat(approved.status()).isEqualTo("EXECUTING");
    assertThat(approved.retryRound()).isEqualTo(1);
    assertThat(approved.retryRequestedBy()).isNull();
    assertThat(retry.verify()).isEqualTo(2);
    drain();

    List<JsonNode> again = requests().subList(4, requests().size());
    assertThat(again).hasSize(2);
    assertThat(again).extracting(r -> r.path("idempotencyKey").asString())
        .containsExactly("bulk-" + id + "-3-a1", "bulk-" + id + "-5-a1");
    assertThat(again).extracting(r -> r.path("creditParty").path("id").asString())
        .containsExactly("01710000002", "01710000004");
    for (JsonNode r : again) {
      assertThat(ContractSchemas.schema(REQUEST_SCHEMA).validate(r)).as(r.toString()).isEmpty();
      assertThat(r.path("txnId").asString()).isNotIn(failed, failedToo);
      assertThat(r.path("attributes").path("lineNo").asString()).isIn("3", "5");
    }

    settle(again.get(0).path("txnId").asString(), "COMPLETED");
    settle(again.get(1).path("txnId").asString(), "COMPLETED");
    BatchView done = batches.get(CHECKER, id);
    assertThat(done.status()).isEqualTo("COMPLETED");
    assertThat(done.progress().completed()).isEqualTo(3);
    assertThat(rowStatuses(id)).containsExactly("COMPLETED", "COMPLETED", "REJECTED", "COMPLETED");
  }

  @Test
  void aFailedAttemptThatMayHavePostedIsNeverRetried() {
    String id = sent(4);
    List<String> txns = requests().stream().map(r -> r.path("txnId").asString()).toList();
    txns.forEach(t -> settle(t, "FAILED"));
    // went through UNKNOWN: the inquiry decided FAILED, but a posting cannot be ruled out here
    DETAILS.put(txns.get(0), detail(txns.get(0), "FAILED", null,
        core("PostTransfer", null) + ",{\"kind\":\"STATE\",\"from\":\"POSTING\",\"to\":\"UNKNOWN\",\"at\":\"2026-11-04T10:00:01Z\"},"
            + "{\"kind\":\"STATE\",\"from\":\"UNKNOWN\",\"to\":\"FAILED\",\"at\":\"2026-11-04T10:00:02Z\"}"));
    // the core answered the posting with success
    DETAILS.put(txns.get(1), detail(txns.get(1), "FAILED", null, core("PostTransfer", "SUCCESS")));
    // carries a core reference
    DETAILS.put(txns.get(2), detail(txns.get(2), "FAILED", "CORE-9", core("ValidateTransaction", "FAILURE")));
    // txns.get(3) is unknown to the orchestrator (404)

    batches.requestRetry(MAKER, id);
    batches.approveRetry(CHECKER, id);
    assertThat(retry.verify()).isEqualTo(4);
    drain();

    assertThat(requests()).hasSize(4);
    assertThat(rowStatuses(id)).containsExactly("FAILED", "FAILED", "FAILED", "FAILED");
    BatchView done = batches.get(CHECKER, id);
    assertThat(done.status()).isEqualTo("COMPLETED");
    assertThat(done.progress().failed()).isEqualTo(4);
  }

  @Test
  void aRepeatedRequestOrApprovalMintsNoSecondAttempt() {
    String id = sent(1);
    String failed = requests().get(0).path("txnId").asString();
    settle(failed, "FAILED");
    DETAILS.put(failed, refusedAtValidation(failed));

    batches.requestRetry(MAKER, id);
    assertThat(code(() -> batches.requestRetry(MAKER_2, id))).isEqualTo("MW-VAL-039");
    batches.approveRetry(CHECKER, id);
    assertThat(code(() -> batches.approveRetry(CHECKER, id))).isEqualTo("MW-VAL-039");
    assertThat(code(() -> batches.requestRetry(MAKER, id))).isEqualTo("MW-VAL-038"); // running again

    assertThat(retry.verify()).isEqualTo(1);
    assertThat(retry.verify()).isZero();
    drain();
    drain();

    List<JsonNode> all = requests();
    assertThat(all).hasSize(2);
    assertThat(all.get(1).path("idempotencyKey").asString()).isEqualTo("bulk-" + id + "-2-a1");
    assertThat(jdbc.queryForObject("select attempt from batch_item where batch_id = ?::uuid", Integer.class, id))
        .isEqualTo(1);

    // the retried attempt fails as well: a second round is attempt 2, never attempt 1 again
    String second = all.get(1).path("txnId").asString();
    settle(second, "FAILED");
    DETAILS.put(second, postingRefused(second));
    batches.requestRetry(MAKER, id);
    batches.approveRetry(CHECKER, id);
    retry.verify();
    drain();
    assertThat(requests()).hasSize(3);
    assertThat(requests().get(2).path("idempotencyKey").asString()).isEqualTo("bulk-" + id + "-2-a2");
    assertThat(batches.get(CHECKER, id).retryRound()).isEqualTo(2);
  }

  @Test
  void theRetryIsAMakerCheckerDecision() {
    String id = sent(1);
    String failed = requests().get(0).path("txnId").asString();
    assertThat(code(() -> batches.requestRetry(MAKER, id))).isEqualTo("MW-VAL-038"); // still running
    settle(failed, "FAILED");

    assertThat(code(() -> batches.requestRetry(CHECKER, id))).isEqualTo("MW-AUTH-002"); // maker role
    assertThat(code(() -> batches.approveRetry(CHECKER, id))).isEqualTo("MW-VAL-039"); // nothing requested
    batches.requestRetry(BOTH, id);
    assertThat(code(() -> batches.approveRetry(BOTH, id))).isEqualTo("MW-AUTH-030");
    assertThat(code(() -> batches.approveRetry(MAKER, id))).isEqualTo("MW-AUTH-002"); // checker role
    assertThat(batches.get(CHECKER, id).status()).isEqualTo("COMPLETED");
    assertThat(requests()).hasSize(1);
  }

  @Test
  void aBatchWithoutFailedRowsHasNothingToRetry() {
    String id = sent(1);
    settle(requests().get(0).path("txnId").asString(), "REJECTED");
    assertThat(code(() -> batches.requestRetry(MAKER, id))).isEqualTo("MW-VAL-038");
  }

  @Test
  void anUnavailableOrchestratorLeavesTheRowWaitingAndALateRecordOfTheOldAttemptChangesNothing() {
    String id = sent(1);
    String failed = requests().get(0).path("txnId").asString();
    settle(failed, "FAILED");
    DETAILS.put(failed, refusedAtValidation(failed));
    batches.requestRetry(MAKER, id);
    batches.approveRetry(CHECKER, id);

    orchestratorDown = true;
    assertThat(retry.verify()).isZero();
    drain();
    assertThat(requests()).hasSize(1);
    assertThat(rowStatuses(id)).containsExactly("RETRY_CHECK");
    assertThat(batches.get(CHECKER, id).status()).isEqualTo("EXECUTING");

    orchestratorDown = false;
    assertThat(retry.verify()).isEqualTo(1);
    settle(failed, "FAILED"); // a redelivered record of the failed attempt
    drain();
    assertThat(requests()).hasSize(2);
    assertThat(rowStatuses(id)).containsExactly("SUBMITTED");
  }
}
