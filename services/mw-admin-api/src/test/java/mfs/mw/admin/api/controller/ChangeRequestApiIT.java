package mfs.mw.admin.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import com.networknt.schema.Schema;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import mfs.mw.admin.api.MutableClock;
import mfs.mw.admin.api.TestInfrastructure;
import mfs.mw.admin.api.contracts.ContractSchemas;
import mfs.mw.admin.api.contracts.OpenApiSchemas;
import mfs.mw.admin.api.scheduler.ChangeRequestExpiryJob;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The maker-checker engine on transaction actions (ticket 37, spec 15.3, acceptance criterion 4):
 * an ops-maker asks, a different ops-checker approves or rejects, a request expires after 72 h. An
 * approval marks the request, audits it and sends the action to mw-orchestrator ({@code
 * mw.txn.action.v1}, or a REVERSAL on {@code mw.txn.request.v1}) through the outbox in one
 * transaction; a rejection or an expiry sends nothing. The orchestrator is a stub answering {@code
 * txn-query.v1} with the status each test sets.
 */
@SpringBootTest(properties = "mw.outbox.relay.enabled=false")
@AutoConfigureMockMvc
@Import({TestInfrastructure.class, ChangeRequestApiIT.TestClock.class})
class ChangeRequestApiIT {

  static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");
  static final String TXN = "01933b8e-7c1a-7b2e-9f3d-2a4c5e6f7a8b";
  static final String MISSING = "01933b8e-7c1a-7b2e-9f3d-2a4c5e6f7a8c";
  static final String CONTRACT = "api/mw-admin-api/admin.v1.openapi.yaml";

  @TestConfiguration(proxyBeanMethods = false)
  static class TestClock {
    @Bean
    @Primary
    Clock testClock() {
      return new MutableClock(NOW);
    }
  }

  /** txnId -> status the stub orchestrator reports. */
  static final Map<String, String> STATUS = new ConcurrentHashMap<>();
  static final HttpServer UPSTREAM = startStub();

  static HttpServer startStub() {
    try {
      HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      s.createContext("/internal/v1/transactions", ChangeRequestApiIT::transactions);
      s.createContext("/token", ex -> respond(ex, 200, "{\"access_token\":\"t\",\"expires_in\":300}"));
      s.start();
      return s;
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  static void transactions(HttpExchange ex) throws IOException {
    String path = ex.getRequestURI().getPath();
    String id = path.substring(path.lastIndexOf('/') + 1);
    String status = STATUS.get(id);
    if (status == null) {
      respond(ex, 404, "{\"code\":\"MW-VAL-003\",\"message\":\"x\",\"correlationId\":\"c\",\"retryable\":false,\"details\":[]}");
      return;
    }
    String txn =
        """
        {"txnId":"%s","txnType":"TOPUP","channel":"CUSTOMER_APP","status":"%s",
         "createdAt":"2026-11-05T08:00:00Z","updatedAt":"2026-11-05T08:01:00Z","correlationId":"corr-1",
         "amount":{"valueMinor":5000,"currency":"BDT"},"fee":{"valueMinor":200,"currency":"BDT"},
         "initiator":{"type":"MSISDN","id":"0171****001"},
         "debitParty":{"type":"WALLET","id":"0171****001"},"creditParty":{"type":"MSISDN","id":"0171****002"}}
        """
            .formatted(id, status);
    respond(ex, 200, "{\"transaction\":" + txn + ",\"timeline\":[],\"partnerCalls\":[]}");
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
    UPSTREAM.stop(0);
  }

  @DynamicPropertySource
  static void upstreams(DynamicPropertyRegistry registry) {
    String base = "http://127.0.0.1:" + UPSTREAM.getAddress().getPort();
    registry.add("mw.admin-api.orchestrator.base-url", () -> base);
    registry.add("mw.admin-api.audit-dw.base-url", () -> base);
    registry.add("mw.admin-api.internal-auth.token-uri", () -> base + "/token");
  }

  private final MockMvcTester mvc;
  private final JdbcTemplate jdbc;
  private final JsonMapper json;
  private final MutableClock clock;
  private final ChangeRequestExpiryJob expiry;

  @Autowired
  ChangeRequestApiIT(MockMvcTester mvc, JdbcTemplate jdbc, JsonMapper json, Clock clock, ChangeRequestExpiryJob expiry) {
    this.mvc = mvc;
    this.jdbc = jdbc;
    this.json = json;
    this.clock = (MutableClock) clock;
    this.expiry = expiry;
  }

  @BeforeEach
  void reset() {
    clock.set(NOW);
    STATUS.clear();
    jdbc.update("delete from outbox");
    jdbc.update("delete from change_request");
  }

  static RequestPostProcessor operator(String username, String... roles) {
    return jwt()
        .jwt(
            j ->
                j.subject("sub-" + username)
                    .issuer("http://keycloak:8080/realms/ops")
                    .claim("preferred_username", username)
                    .claim("realm_access", Map.of("roles", List.of(roles))));
  }

  static final RequestPostProcessor MAKER = operator("ops-maker01", "viewer", "ops-maker");
  static final RequestPostProcessor CHECKER = operator("ops-checker01", "viewer", "ops-checker");

  private JsonNode body(MvcTestResult r) {
    return json.readTree(r.getResponse().getContentAsByteArray());
  }

  private MvcTestResult ask(RequestPostProcessor who, String txnId, String body) {
    return mvc.post().uri("/admin/v1/transactions/{id}/change-requests", txnId).with(who)
        .contentType(MediaType.APPLICATION_JSON).content(body).exchange();
  }

  private String asked(String txnId, String action) {
    MvcTestResult r = ask(MAKER, txnId, "{\"action\":\"" + action + "\",\"reason\":\"checked with the partner\"}");
    assertThat(r).hasStatus(201);
    JsonNode cr = body(r);
    assertValid("/admin/v1/transactions/{txnId}/change-requests", "post", "201", cr);
    return cr.path("id").asString();
  }

  private MvcTestResult approve(RequestPostProcessor who, String id) {
    return mvc.post().uri("/admin/v1/change-requests/{id}/approve", id).with(who).exchange();
  }

  private MvcTestResult reject(RequestPostProcessor who, String id, String reason) {
    return mvc.post().uri("/admin/v1/change-requests/{id}/reject", id).with(who)
        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"" + reason + "\"}").exchange();
  }

  private List<JsonNode> outbox(String topic) {
    return jdbc.queryForList("select payload::text from outbox where topic = ? order by id", String.class, topic)
        .stream().map(json::readTree).toList();
  }

  private List<JsonNode> audits(String action) {
    return outbox("mw.audit.event.v1").stream().filter(e -> e.path("action").asString().equals(action)).toList();
  }

  private static void assertValid(String path, String method, String status, JsonNode body) {
    Schema schema = OpenApiSchemas.response(CONTRACT, path, method, status);
    assertThat(schema.validate(body)).as("%s", body).isEmpty();
  }

  private static void assertEvent(String schema, JsonNode e) {
    assertThat(ContractSchemas.schema(schema).validate(e)).as("%s", e).isEmpty();
  }

  private void assertNothingSentToTheOrchestrator() {
    assertThat(outbox("mw.txn.action.v1")).isEmpty();
    assertThat(outbox("mw.txn.request.v1")).isEmpty();
  }

  // --- maker != checker -----------------------------------------------------------------------

  @Test
  void theMakerCannotApproveOrRejectOwnRequestEvenHoldingTheCheckerRole() {
    STATUS.put(TXN, "MANUAL_REVIEW");
    RequestPostProcessor both = operator("ops-both01", "ops-maker", "ops-checker");
    MvcTestResult created = ask(both, TXN, "{\"action\":\"FORCE_COMMIT\",\"reason\":\"partner confirmed\"}");
    assertThat(created).hasStatus(201);
    String id = body(created).path("id").asString();

    for (MvcTestResult r : List.of(approve(both, id), reject(both, id, "no"))) {
      assertThat(r).hasStatus(403);
      assertThat(body(r).path("code").asString()).isEqualTo("MW-AUTH-030");
    }
    assertNothingSentToTheOrchestrator();
    assertThat(jdbc.queryForObject("select status from change_request where id = ?::uuid", String.class, id))
        .isEqualTo("PENDING");
  }

  @Test
  void onlyTheMakerRoleMayAskAndOnlyTheCheckerRoleMayDecide() {
    STATUS.put(TXN, "MANUAL_REVIEW");
    for (RequestPostProcessor notMaker : List.of(CHECKER, operator("emergency01", "ops-emergency"))) {
      MvcTestResult r = ask(notMaker, TXN, "{\"action\":\"FORCE_COMMIT\",\"reason\":\"x\"}");
      assertThat(r).hasStatus(403);
      assertThat(body(r).path("code").asString()).isEqualTo("MW-AUTH-002");
    }
    assertThat(ask(operator("viewer01", "viewer"), TXN, "{\"action\":\"FORCE_COMMIT\",\"reason\":\"x\"}"))
        .hasStatus(403);

    String id = asked(TXN, "FORCE_COMMIT");
    MvcTestResult byOtherMaker = approve(operator("ops-maker02", "ops-maker"), id);
    assertThat(byOtherMaker).hasStatus(403);
    assertThat(body(byOtherMaker).path("code").asString()).isEqualTo("MW-AUTH-002");
    assertNothingSentToTheOrchestrator();
  }

  // --- expiry ---------------------------------------------------------------------------------

  @Test
  void anExpiredRequestCannotBeApprovedAndIsMarkedExpiredWithAnAudit() {
    STATUS.put(TXN, "MANUAL_REVIEW");
    String id = asked(TXN, "FORCE_RELEASE");
    clock.advance(Duration.ofHours(72));

    MvcTestResult r = approve(CHECKER, id);

    assertThat(r).hasStatus(409);
    assertThat(body(r).path("code").asString()).isEqualTo("MW-VAL-032");
    MvcTestResult view = mvc.get().uri("/admin/v1/change-requests/{id}", id).with(CHECKER).exchange();
    assertThat(body(view).path("status").asString()).isEqualTo("EXPIRED");
    assertNothingSentToTheOrchestrator();
    JsonNode audit = audits("admin.change_request.expired").getFirst();
    assertThat(audit.path("before").path("status").asString()).isEqualTo("PENDING");
    assertThat(audit.path("after").path("status").asString()).isEqualTo("EXPIRED");
    assertEvent("events/mw-audit-dw/mw.audit.event.v1.schema.json", audit);
  }

  @Test
  void theExpiryJobExpiresEveryRequestOlderThan72Hours() {
    STATUS.put(TXN, "MANUAL_REVIEW");
    String old = asked(TXN, "FORCE_COMMIT");
    clock.advance(Duration.ofHours(71));
    expiry.expire();
    assertThat(jdbc.queryForObject("select status from change_request where id = ?::uuid", String.class, old))
        .isEqualTo("PENDING");

    clock.advance(Duration.ofHours(1));
    expiry.expire();

    assertThat(jdbc.queryForObject("select status from change_request where id = ?::uuid", String.class, old))
        .isEqualTo("EXPIRED");
    assertThat(audits("admin.change_request.expired")).hasSize(1);
    // the transaction is free for a new request
    assertThat(ask(MAKER, TXN, "{\"action\":\"FORCE_COMMIT\",\"reason\":\"again\"}")).hasStatus(201);
  }

  // --- approve / reject -----------------------------------------------------------------------

  @Test
  void anApprovedReversalSendsOneFullAmountReversalAndIsAuditedWithBeforeAndAfter() {
    STATUS.put(TXN, "COMPLETED");
    String id = asked(TXN, "REVERSE");

    MvcTestResult r = approve(CHECKER, id);

    assertThat(r).hasStatus(200);
    JsonNode cr = body(r);
    assertValid("/admin/v1/change-requests/{id}/approve", "post", "200", cr);
    assertThat(cr.path("status").asString()).isEqualTo("APPROVED");
    assertThat(cr.path("checkerId").asString()).isEqualTo("ops-checker01");
    assertThat(cr.path("diff").path("before").path("status").asString()).isEqualTo("COMPLETED");
    assertThat(cr.path("diff").path("after").path("status").asString()).isEqualTo("REVERSING");

    List<JsonNode> requests = outbox("mw.txn.request.v1");
    assertThat(requests).hasSize(1);
    JsonNode rev = requests.getFirst();
    assertEvent("events/mw-orchestrator/mw.txn.request.v1.schema.json", rev);
    assertThat(rev.path("txnType").asString()).isEqualTo("REVERSAL");
    assertThat(rev.path("channel").asString()).isEqualTo("SYSTEM");
    assertThat(rev.path("originalTxnId").asString()).isEqualTo(TXN);
    assertThat(rev.path("amount").path("valueMinor").asLong()).isEqualTo(5000);
    assertThat(rev.path("idempotencyKey").asString()).isEqualTo(id);
    assertThat(cr.path("payload").path("reversalTxnId").asString()).isEqualTo(rev.path("txnId").asString());
    assertThat(rev.toString()).doesNotContain("0171****");

    JsonNode audit = audits("admin.change_request.approved").getFirst();
    assertThat(audit.path("actor").path("id").asString()).isEqualTo("ops-checker01");
    assertThat(audit.path("txnId").asString()).isEqualTo(TXN);
    assertThat(audit.path("before").path("status").asString()).isEqualTo("PENDING");
    assertThat(audit.path("before").path("txnStatus").asString()).isEqualTo("COMPLETED");
    assertThat(audit.path("after").path("status").asString()).isEqualTo("APPROVED");
    assertThat(audit.path("after").path("txnStatus").asString()).isEqualTo("REVERSING");
    assertEvent("events/mw-audit-dw/mw.audit.event.v1.schema.json", audit);

    // decided once: a second approval is refused and sends nothing more
    MvcTestResult again = approve(operator("ops-checker02", "ops-checker"), id);
    assertThat(again).hasStatus(409);
    assertThat(body(again).path("code").asString()).isEqualTo("MW-VAL-033");
    assertThat(outbox("mw.txn.request.v1")).hasSize(1);
  }

  @Test
  void aRejectedReversalChangesNothing() {
    STATUS.put(TXN, "COMPLETED");
    String id = asked(TXN, "REVERSE");

    MvcTestResult r = reject(CHECKER, id, "customer withdrew the complaint");

    assertThat(r).hasStatus(200);
    JsonNode cr = body(r);
    assertValid("/admin/v1/change-requests/{id}/reject", "post", "200", cr);
    assertThat(cr.path("status").asString()).isEqualTo("REJECTED");
    assertThat(cr.path("decisionReason").asString()).isEqualTo("customer withdrew the complaint");
    assertNothingSentToTheOrchestrator();
    JsonNode audit = audits("admin.change_request.rejected").getFirst();
    assertThat(audit.path("before").path("status").asString()).isEqualTo("PENDING");
    assertThat(audit.path("after").path("status").asString()).isEqualTo("REJECTED");
    assertEvent("events/mw-audit-dw/mw.audit.event.v1.schema.json", audit);
    assertThat(approve(CHECKER, id)).hasStatus(409);
    assertNothingSentToTheOrchestrator();
  }

  @Test
  void anApprovedForceCommitAndInquirySendAnOperatorAction() {
    STATUS.put(TXN, "MANUAL_REVIEW");
    String id = asked(TXN, "FORCE_COMMIT");
    assertThat(approve(CHECKER, id)).hasStatus(200);
    String other = "01933b8e-7c1a-7b2e-9f3d-2a4c5e6f7a8d";
    STATUS.put(other, "UNKNOWN");
    assertThat(approve(CHECKER, asked(other, "INQUIRE"))).hasStatus(200);

    List<JsonNode> actions = outbox("mw.txn.action.v1");
    assertThat(actions).hasSize(2);
    actions.forEach(a -> assertEvent("events/mw-admin-api/mw.txn.action.v1.schema.json", a));
    JsonNode force = actions.getFirst();
    assertThat(force.path("txnId").asString()).isEqualTo(TXN);
    assertThat(force.path("action").asString()).isEqualTo("FORCE_COMMIT");
    assertThat(force.path("changeRequestId").asString()).isEqualTo(id);
    assertThat(force.path("makerId").asString()).isEqualTo("ops-maker01");
    assertThat(force.path("checkerId").asString()).isEqualTo("ops-checker01");
    assertThat(actions.get(1).path("action").asString()).isEqualTo("INQUIRE");
    assertThat(audits("admin.change_request.created")).hasSize(2);
    assertThat(audits("admin.change_request.approved")).hasSize(2);
  }

  @Test
  void anApprovalIsRefusedWhenTheTransactionMovedOnAndTheRequestStaysPending() {
    STATUS.put(TXN, "MANUAL_REVIEW");
    String id = asked(TXN, "FORCE_COMMIT");
    STATUS.put(TXN, "COMPLETED");

    MvcTestResult r = approve(CHECKER, id);

    assertThat(r).hasStatus(409);
    assertThat(body(r).path("code").asString()).isEqualTo("MW-VAL-030");
    assertThat(jdbc.queryForObject("select status from change_request where id = ?::uuid", String.class, id))
        .isEqualTo("PENDING");
    assertNothingSentToTheOrchestrator();
  }

  // --- creation rules -------------------------------------------------------------------------

  @Test
  void anActionThatDoesNotFitTheStatusIsRefusedAndOneTransactionHasOneOpenRequest() {
    STATUS.put(TXN, "COMPLETED");
    for (String action : List.of("FORCE_COMMIT", "FORCE_RELEASE", "INQUIRE")) {
      MvcTestResult r = ask(MAKER, TXN, "{\"action\":\"" + action + "\",\"reason\":\"x\"}");
      assertThat(r).as(action).hasStatus(409);
      assertThat(body(r).path("code").asString()).isEqualTo("MW-VAL-030");
    }
    asked(TXN, "REVERSE");
    MvcTestResult second = ask(MAKER, TXN, "{\"action\":\"REVERSE\",\"reason\":\"again\"}");
    assertThat(second).hasStatus(409);
    assertThat(body(second).path("code").asString()).isEqualTo("MW-VAL-031");

    assertThat(ask(MAKER, MISSING, "{\"action\":\"REVERSE\",\"reason\":\"x\"}")).hasStatus(404);
    for (String bad : List.of("{\"action\":\"DELETE\",\"reason\":\"x\"}", "{\"action\":\"REVERSE\"}",
        "{\"action\":\"REVERSE\",\"reason\":\"x\",\"amount\":1}", "not json")) {
      MvcTestResult r = ask(MAKER, TXN, bad);
      assertThat(r).as(bad).hasStatus(400);
      assertThat(body(r).path("code").asString()).isEqualTo("MW-VAL-001");
    }
  }

  @Test
  void checkersListPendingRequests() {
    STATUS.put(TXN, "MANUAL_REVIEW");
    String id = asked(TXN, "FORCE_COMMIT");

    MvcTestResult r = mvc.get().uri("/admin/v1/change-requests?status=PENDING").with(CHECKER).exchange();

    assertThat(r).hasStatus(200);
    JsonNode page = body(r);
    assertValid("/admin/v1/change-requests", "get", "200", page);
    assertThat(page.path("items")).hasSize(1);
    assertThat(page.path("items").get(0).path("id").asString()).isEqualTo(id);
    assertThat(page.path("items").get(0).path("makerId").asString()).isEqualTo("ops-maker01");
    assertThat(mvc.get().uri("/admin/v1/change-requests/{id}", MISSING).with(CHECKER).exchange()).hasStatus(404);
    // integration staff read partner change requests only (ticket 38): no transaction request shown
    RequestPostProcessor integration = operator("int01", "integration-maker");
    MvcTestResult partnersOnly = mvc.get().uri("/admin/v1/change-requests").with(integration).exchange();
    assertThat(partnersOnly).hasStatus(200);
    assertThat(body(partnersOnly).path("items")).isEmpty();
    assertThat(mvc.get().uri("/admin/v1/change-requests?resourceType=transaction").with(integration).exchange())
        .hasStatus(403);
    assertThat(mvc.get().uri("/admin/v1/change-requests/{id}", id).with(integration).exchange()).hasStatus(403);
    assertThat(mvc.get().uri("/admin/v1/change-requests").with(operator("sec01", "security-admin")).exchange())
        .hasStatus(403);
  }
}
