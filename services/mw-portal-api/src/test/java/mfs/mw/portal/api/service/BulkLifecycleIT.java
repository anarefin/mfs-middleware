package mfs.mw.portal.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.networknt.schema.Schema;
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
 * The bulk batch lifecycle around execution (M3 gate decisions, tickets 33/34): pending batches
 * expire after 72 h; upload, approve, reject, expire, pause and resume are audited on {@code
 * mw.audit.event.v1} with the operator as actor; SUBMITTED rows that never settled are reconciled
 * through mw-orchestrator's authenticated internal query API (a stub here), and a batch with a
 * MANUAL_REVIEW or unresolved row ends COMPLETED_WITH_EXCEPTIONS, as its result file says.
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
      "mw.portal-api.bulk.expiry.enabled=false"
    })
@Import({TestInfrastructure.class, BulkLifecycleIT.TestClock.class})
class BulkLifecycleIT {

  static final Instant START = Instant.parse("2026-11-04T10:00:00Z");
  static final String AUDIT_SCHEMA = "events/mw-audit-dw/mw.audit.event.v1.schema.json";
  static final String TOKEN = "lifecycle-token";

  @TestConfiguration(proxyBeanMethods = false)
  static class TestClock {
    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock(START);
    }
  }

  // --- orchestrator stub: txnId -> answer (status, errorCode) or absent (404) --------------------

  static final Map<String, String[]> ANSWERS = new ConcurrentHashMap<>();
  static volatile boolean orchestratorDown;
  static final HttpServer ORCHESTRATOR = startStub();

  static HttpServer startStub() {
    try {
      HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      s.createContext("/internal/v1/transactions/", BulkLifecycleIT::answer);
      s.createContext("/token", ex -> respond(ex, 200, "{\"access_token\":\"" + TOKEN + "\",\"expires_in\":300}"));
      s.start();
      return s;
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  static void answer(HttpExchange ex) throws IOException {
    String path = ex.getRequestURI().getPath();
    String query = String.valueOf(ex.getRequestURI().getRawQuery());
    String txnId = path.substring(path.lastIndexOf('/') + 1);
    String[] a = ANSWERS.get(txnId);
    if (!("Bearer " + TOKEN).equals(ex.getRequestHeaders().getFirst("Authorization"))) {
      respond(ex, 401, "{}");
    } else if (orchestratorDown) {
      respond(ex, 500, "{}");
    } else if (a == null || !query.contains("orgId=ORG001")) {
      respond(ex, 404, "{\"code\":\"MW-VAL-003\",\"message\":\"x\",\"correlationId\":\"c\",\"retryable\":false,\"details\":[]}");
    } else {
      String error = a[1] == null ? "" : ",\"error\":{\"code\":\"" + a[1] + "\",\"message\":\"x\",\"retryable\":false}";
      respond(
          ex,
          200,
          "{\"transaction\":{\"txnId\":\"" + txnId + "\",\"txnType\":\"BULK_DISBURSE\",\"status\":\"" + a[0]
              + "\",\"createdAt\":\"2026-11-04T10:00:00Z\",\"updatedAt\":\"2026-11-04T10:00:01Z\"" + error
              + "},\"timeline\":[],\"partnerCalls\":[]}");
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

  static final PortalUser MAKER = new PortalUser("maker-sub-1", "org-maker01", "ORG001", Set.of("org-maker"));
  static final PortalUser CHECKER = new PortalUser("checker-sub-1", "org-checker01", "ORG001", Set.of("org-checker"));

  private final BatchService batches;
  private final BulkExecutionService execution;
  private final BatchExpiryService expiry;
  private final BulkReconcileService reconcile;
  private final MutableClock clock;
  private final JdbcTemplate jdbc;
  private final JsonMapper json;

  @Autowired
  BulkLifecycleIT(
      BatchService batches, BulkExecutionService execution, BatchExpiryService expiry,
      BulkReconcileService reconcile, MutableClock clock, JdbcTemplate jdbc, JsonMapper json) {
    this.batches = batches;
    this.execution = execution;
    this.expiry = expiry;
    this.reconcile = reconcile;
    this.clock = clock;
    this.jdbc = jdbc;
    this.json = json;
  }

  @BeforeEach
  void clean() {
    clock.set(START);
    ANSWERS.clear();
    orchestratorDown = false;
    jdbc.update("delete from outbox");
    jdbc.update("delete from batch_item");
    jdbc.update("delete from batch");
    execution.resetThrottle();
  }

  static byte[] rows(int n) {
    StringBuilder b = new StringBuilder("msisdn,amount,reference\n");
    for (int i = 1; i <= n; i++) {
      b.append("0171").append(String.format("%07d", i)).append(",1.25,SAL-").append(i).append('\n');
    }
    return b.toString().getBytes(StandardCharsets.UTF_8);
  }

  private List<JsonNode> audits() {
    return jdbc.queryForList(
            "select payload::text from outbox where topic = 'mw.audit.event.v1' order by id", String.class)
        .stream()
        .map(json::readTree)
        .toList();
  }

  private List<String> childTxnIds(String batchId) {
    return jdbc.queryForList(
        "select txn_id::text from batch_item where batch_id = ?::uuid order by line_no", String.class, batchId);
  }

  /** An approved batch whose rows were all sent (SUBMITTED). */
  private String submitted(int n) {
    String id = batches.upload(MAKER, rows(n)).batchId();
    batches.approve(CHECKER, id);
    clock.advance(Duration.ofSeconds(1));
    execution.dispatch();
    clock.advance(Duration.ofSeconds(1));
    execution.dispatch();
    assertThat(childTxnIds(id)).doesNotContainNull().hasSize(n);
    return id;
  }

  // --- expiry ----------------------------------------------------------------------------------

  @Test
  void aBatchPendingFor72HoursExpiresAndCanNoLongerBeDecided() {
    String old = batches.upload(MAKER, rows(2)).batchId();
    clock.advance(Duration.ofHours(1));
    String young = batches.upload(MAKER, rows(1)).batchId();

    clock.advance(Duration.ofHours(71)); // old is exactly 72 h pending: not yet
    assertThat(expiry.expirePending()).isZero();
    clock.advance(Duration.ofSeconds(1));
    assertThat(expiry.expirePending()).isEqualTo(1);
    assertThat(expiry.expirePending()).isZero();

    BatchView expired = batches.get(CHECKER, old);
    assertThat(expired.status()).isEqualTo("EXPIRED");
    assertThat(expired.decidedAt()).isEqualTo(clock.instant());
    assertThat(batches.get(CHECKER, young).status()).isEqualTo("PENDING_APPROVAL");
    assertThatThrownBy(() -> batches.approve(CHECKER, old))
        .isInstanceOfSatisfying(MwException.class, e -> assertThat(e.code()).isEqualTo("MW-VAL-032"));
    assertThatThrownBy(() -> batches.resultFile(MAKER, old))
        .isInstanceOfSatisfying(MwException.class, e -> assertThat(e.code()).isEqualTo("MW-VAL-035"));

    JsonNode audit = audits().getLast();
    assertThat(audit.path("action").asString()).isEqualTo("bulk.batch.expired");
    assertThat(audit.path("actor").path("type").asString()).isEqualTo("system");
    assertThat(audit.path("actor").path("id").asString()).isEqualTo("mw-portal-api");
    assertThat(audit.path("resource").asString()).isEqualTo("bulk-batch/" + old);
    assertThat(audit.path("before").path("status").asString()).isEqualTo("PENDING_APPROVAL");
    assertThat(audit.path("after").path("status").asString()).isEqualTo("EXPIRED");
  }

  // --- audit -----------------------------------------------------------------------------------

  @Test
  void everyOperatorActionIsAuditedWithTheOperatorAsActorAndNoCustomerData() {
    String a = batches.upload(MAKER, rows(2)).batchId();
    batches.approve(CHECKER, a);
    batches.pause(CHECKER, a);
    batches.resume(CHECKER, a);
    String r = batches.upload(MAKER, rows(1)).batchId();
    batches.reject(CHECKER, r, "wrong month for 01711000001");

    List<JsonNode> events = audits();
    assertThat(events.stream().map(e -> e.path("action").asString()).toList())
        .containsExactly(
            "bulk.batch.uploaded", "bulk.batch.approved", "bulk.batch.paused", "bulk.batch.resumed",
            "bulk.batch.uploaded", "bulk.batch.rejected");
    assertThat(events.stream().map(e -> e.path("actor").path("id").asString()).toList())
        .containsExactly("org-maker01", "org-checker01", "org-checker01", "org-checker01", "org-maker01", "org-checker01");

    Schema schema = ContractSchemas.schema(AUDIT_SCHEMA);
    for (JsonNode e : events) {
      assertThat(schema.validate(e)).as("%s", e).isEmpty();
      assertThat(e.path("actor").path("type").asString()).isEqualTo("user");
      assertThat(e.path("service").asString()).isEqualTo("mw-portal-api");
      assertThat(e.path("outcome").asString()).isEqualTo("SUCCESS");
      // no customer MSISDN in clear anywhere (the batch's rows are never copied into the audit)
      assertThat(e.toString()).doesNotContain("01711000001").doesNotContain("01711000002");
    }
    JsonNode upload = events.get(0);
    assertThat(upload.path("resource").asString()).isEqualTo("bulk-batch/" + a);
    assertThat(upload.has("before")).isFalse();
    assertThat(upload.path("after").path("status").asString()).isEqualTo("PENDING_APPROVAL");
    assertThat(upload.path("after").path("rowCount").asInt()).isEqualTo(2);
    assertThat(upload.path("after").path("orgId").asString()).isEqualTo("ORG001");
    JsonNode approve = events.get(1);
    assertThat(approve.path("before").path("status").asString()).isEqualTo("PENDING_APPROVAL");
    assertThat(approve.path("after").path("status").asString()).isEqualTo("APPROVED");
    assertThat(approve.path("txnId").asString()).isEqualTo(batches.get(CHECKER, a).parentTxnId());
    assertThat(events.get(2).path("after").path("status").asString()).isEqualTo("PAUSED");
    assertThat(events.get(3).path("after").path("status").asString()).isEqualTo("EXECUTING");
    JsonNode reject = events.get(5);
    assertThat(reject.path("after").path("status").asString()).isEqualTo("REJECTED");
    assertThat(reject.path("after").path("reason").asString()).isEqualTo("wrong month for 0171****001");
  }

  @Test
  void aRefusedActionIsNotAudited() {
    String a = batches.upload(MAKER, rows(1)).batchId();
    assertThatThrownBy(() -> batches.resume(CHECKER, a)).isInstanceOf(MwException.class);
    assertThat(audits()).hasSize(1);
  }

  // --- reconcile and COMPLETED_WITH_EXCEPTIONS -------------------------------------------------

  @Test
  void staleSubmittedRowsAreSettledFromTheOrchestratorAndManualReviewEndsWithExceptions() {
    String id = submitted(3);
    List<String> txns = childTxnIds(id);
    ANSWERS.put(txns.get(0), new String[] {"COMPLETED", null});
    ANSWERS.put(txns.get(1), new String[] {"MANUAL_REVIEW", "MW-TMO-004"});
    ANSWERS.put(txns.get(2), new String[] {"POSTING", null});

    // too young: nothing is asked
    clock.advance(Duration.ofMinutes(9));
    assertThat(reconcile.reconcile()).isZero();
    assertThat(batches.get(CHECKER, id).status()).isEqualTo("EXECUTING");

    clock.advance(Duration.ofMinutes(2));
    assertThat(reconcile.reconcile()).isEqualTo(2);
    assertThat(batches.get(CHECKER, id).status()).isEqualTo("EXECUTING"); // row 3 still running

    ANSWERS.put(txns.get(2), new String[] {"REJECTED", "MW-CORE-INSUFFICIENT_BALANCE"});
    clock.advance(Duration.ofMinutes(2));
    assertThat(reconcile.reconcile()).isEqualTo(1);

    BatchView done = batches.get(CHECKER, id);
    assertThat(done.status()).isEqualTo("COMPLETED_WITH_EXCEPTIONS");
    assertThat(done.completedAt()).isNotNull();
    assertThat(done.progress().completed()).isEqualTo(1);
    assertThat(done.progress().manualReview()).isEqualTo(1);
    assertThat(done.progress().failed()).isEqualTo(1);
    assertThat(batches.resultFile(MAKER, id).lines().toList())
        .containsExactly(
            "line,msisdn,amount,reference,status,txnId,errorCode,batchStatus",
            "2,0171****001,1.25,SAL-1,COMPLETED," + txns.get(0) + ",,COMPLETED_WITH_EXCEPTIONS",
            "3,0171****002,1.25,SAL-2,MANUAL_REVIEW," + txns.get(1) + ",MW-TMO-004,COMPLETED_WITH_EXCEPTIONS",
            "4,0171****003,1.25,SAL-3,REJECTED," + txns.get(2) + ",MW-CORE-INSUFFICIENT_BALANCE,COMPLETED_WITH_EXCEPTIONS");
  }

  @Test
  void aRowTheOrchestratorCannotResolveInTimeEndsUnresolved() {
    String id = submitted(2);
    List<String> txns = childTxnIds(id);
    ANSWERS.put(txns.get(0), new String[] {"COMPLETED", null});
    // txns.get(1): unknown to the orchestrator (404)

    clock.advance(Duration.ofMinutes(11));
    assertThat(reconcile.reconcile()).isEqualTo(1);
    assertThat(batches.get(CHECKER, id).status()).isEqualTo("EXECUTING");

    clock.advance(Duration.ofHours(24));
    assertThat(reconcile.reconcile()).isEqualTo(1);
    BatchView done = batches.get(CHECKER, id);
    assertThat(done.status()).isEqualTo("COMPLETED_WITH_EXCEPTIONS");
    assertThat(done.progress().unresolved()).isEqualTo(1);
    assertThat(batches.resultFile(MAKER, id).lines().toList().get(2))
        .isEqualTo("3,0171****002,1.25,SAL-2,UNRESOLVED," + txns.get(1) + ",,COMPLETED_WITH_EXCEPTIONS");

    // a late status record still settles the row; the batch keeps its final status
    ANSWERS.put(txns.get(1), new String[] {"COMPLETED", null});
    assertThat(reconcile.reconcile()).isZero();
  }

  @Test
  void anUnavailableOrchestratorChangesNothing() {
    String id = submitted(2);
    orchestratorDown = true;
    clock.advance(Duration.ofHours(30));

    assertThat(reconcile.reconcile()).isZero();
    assertThat(batches.get(CHECKER, id).status()).isEqualTo("EXECUTING");
    assertThat(batches.get(CHECKER, id).progress().inFlight()).isEqualTo(2);
  }

  @Test
  void aBatchWhoseRowsAllCompleteIsPlainCompleted() {
    String id = submitted(1);
    ANSWERS.put(childTxnIds(id).get(0), new String[] {"COMPLETED", null});
    clock.advance(Duration.ofMinutes(11));
    reconcile.reconcile();
    assertThat(batches.get(CHECKER, id).status()).isEqualTo("COMPLETED");
  }
}
