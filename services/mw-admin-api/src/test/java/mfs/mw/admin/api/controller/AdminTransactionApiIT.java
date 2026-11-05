package mfs.mw.admin.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import com.networknt.schema.Schema;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import mfs.mw.admin.api.TestInfrastructure;
import mfs.mw.admin.api.contracts.ContractSchemas;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Operators search transactions and open one with its saga timeline and audit trail (ticket 36,
 * spec 15.2, 15.4): {@code /admin/v1/transactions} on a realm {@code ops} token, roles of spec 15.4
 * per endpoint, data from mw-orchestrator's and mw-audit-dw's internal query APIs (here one stub
 * HTTP server speaking {@code txn-query.v1} and {@code audit-query.v1}). Only compliance-officer and
 * auditor get an unmasked MSISDN, and every such reveal writes a {@code mw.audit.event.v1} record.
 */
@SpringBootTest(properties = "mw.outbox.relay.enabled=false")
@AutoConfigureMockMvc
@Import(TestInfrastructure.class)
class AdminTransactionApiIT {

  static final String TXNS = "/admin/v1/transactions";
  static final String TXN = "01933b8e-7c1a-7b2e-9f3d-2a4c5e6f7a8b";
  static final String MISSING_TXN = "01933b8e-7c1a-7b2e-9f3d-2a4c5e6f7a8c";
  static final String RAW_MSISDN = "01711000001";
  static final String AUDIT_TOPIC = "mw.audit.event.v1";

  static String txnJson(String initiator, String credit) {
    return """
        {"txnId":"%s","txnType":"P2P","channel":"APP","status":"COMPLETED","previousStatus":"POSTING",
         "createdAt":"2026-11-03T08:15:30Z","updatedAt":"2026-11-03T08:15:32Z","correlationId":"corr-1",
         "amount":{"valueMinor":5000,"currency":"BDT"},"fee":{"valueMinor":0,"currency":"BDT"},
         "initiator":{"type":"MSISDN","id":"%s"},
         "debitParty":{"type":"WALLET","id":"%s","name":"R.","walletType":"CUSTOMER"},
         "creditParty":{"type":"WALLET","id":"%s","name":"K.","walletType":"CUSTOMER"},
         "coreRef":"CORE-1"}
        """
        .formatted(TXN, initiator, initiator, credit);
  }

  static final String MASKED_TXN = txnJson("0171****001", "0171****002");
  static final String REVEALED_TXN = txnJson(RAW_MSISDN, "01711000002");

  static final String TIMELINE =
      "[{\"kind\":\"STATE\",\"from\":\"RECEIVED\",\"to\":\"VALIDATING\",\"trigger\":\"TxnRequested\",\"at\":\"2026-11-03T08:15:30Z\"},"
          + "{\"kind\":\"CORE\",\"command\":\"PostTransfer\",\"commandId\":\"c1\",\"sends\":1,\"result\":\"TransferPosted\","
          + "\"outcome\":\"SUCCESS\",\"at\":\"2026-11-03T08:15:31Z\",\"resultAt\":\"2026-11-03T08:15:32Z\"},"
          + "{\"kind\":\"STATE\",\"from\":\"POSTING\",\"to\":\"COMPLETED\",\"trigger\":\"Posted\",\"at\":\"2026-11-03T08:15:32Z\"}]";

  static final String AUDIT_PAGE =
      """
      {"items":[
        {"auditId":"0d6c3f0e-1111-4b5e-9a1e-000000000001","month":"2026-11","seq":7,
         "recordedAt":"2026-11-03T08:15:33Z","at":"2026-11-03T08:15:30Z","txnId":"%1$s","correlationId":"corr-1",
         "actor":{"type":"system","id":"mw-orchestrator"},"action":"txn.state.changed","resource":"transaction/%1$s",
         "outcome":"SUCCESS","service":"mw-orchestrator","after":{"status":"VALIDATING"}},
        {"auditId":"0d6c3f0e-1111-4b5e-9a1e-000000000002","month":"2026-11","seq":9,
         "recordedAt":"2026-11-03T08:15:34Z","at":"2026-11-03T08:15:32Z","txnId":"%1$s","correlationId":"corr-1",
         "actor":{"type":"system","id":"mw-orchestrator"},"action":"txn.state.changed","resource":"transaction/%1$s",
         "outcome":"SUCCESS","service":"mw-orchestrator","before":{"status":"POSTING"},"after":{"status":"COMPLETED"}}],
       "limit":500,"offset":0}
      """
          .formatted(TXN);

  static final String TOKEN = "service-token-1";
  static final List<String> QUERIES = new CopyOnWriteArrayList<>();
  static volatile boolean orchestratorDown;
  static final HttpServer UPSTREAM = startStub();

  static HttpServer startStub() {
    try {
      HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      s.createContext("/internal/v1/transactions", AdminTransactionApiIT::transactions);
      s.createContext("/internal/v1/audit/events", AdminTransactionApiIT::audit);
      s.createContext("/token", ex -> respond(ex, 200, "{\"access_token\":\"" + TOKEN + "\",\"expires_in\":300}"));
      s.start();
      return s;
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  static String query(HttpExchange ex) {
    String q = ex.getRequestURI().getRawQuery();
    return q == null ? "" : URLDecoder.decode(q, StandardCharsets.UTF_8);
  }

  static boolean authorised(HttpExchange ex) throws IOException {
    if (("Bearer " + TOKEN).equals(ex.getRequestHeaders().getFirst("Authorization"))) {
      return true;
    }
    respond(ex, 401, "{\"code\":\"MW-AUTH-001\",\"message\":\"x\",\"correlationId\":\"c\",\"retryable\":false,\"details\":[]}");
    return false;
  }

  static void transactions(HttpExchange ex) throws IOException {
    String path = ex.getRequestURI().getPath();
    String query = query(ex);
    QUERIES.add(path + "?" + query);
    if (!authorised(ex)) {
      return;
    }
    if (orchestratorDown) {
      respond(ex, 500, "{\"code\":\"MW-SYS-001\",\"message\":\"x\",\"correlationId\":\"c\",\"retryable\":true,\"details\":[]}");
    } else if (path.endsWith("/transactions")) {
      boolean match = query.contains("txnId=" + TXN) || query.contains("msisdn=+8801711000001") || query.contains("msisdn=" + RAW_MSISDN);
      respond(ex, 200, "{\"items\":[" + (match ? MASKED_TXN : "") + "],\"limit\":50,\"offset\":0}");
    } else if (path.endsWith(TXN)) {
      String txn = query.contains("reveal=true") ? REVEALED_TXN : MASKED_TXN;
      respond(ex, 200, "{\"transaction\":" + txn + ",\"timeline\":" + TIMELINE + ",\"partnerCalls\":[]}");
    } else {
      respond(ex, 404, "{\"code\":\"MW-VAL-003\",\"message\":\"Transaction not found\",\"correlationId\":\"c\",\"retryable\":false,\"details\":[]}");
    }
  }

  static void audit(HttpExchange ex) throws IOException {
    QUERIES.add(ex.getRequestURI().getPath() + "?" + query(ex));
    if (authorised(ex)) {
      respond(ex, 200, AUDIT_PAGE);
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

  @Autowired
  AdminTransactionApiIT(MockMvcTester mvc, JdbcTemplate jdbc, JsonMapper json) {
    this.mvc = mvc;
    this.jdbc = jdbc;
    this.json = json;
  }

  @BeforeEach
  void reset() {
    QUERIES.clear();
    orchestratorDown = false;
    jdbc.update("delete from outbox");
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

  /** The roles of spec 15.4 that may read transactions, and those that may not. */
  static final List<String> READERS =
      List.of("viewer", "ops-maker", "ops-checker", "ops-emergency", "recon-maker", "recon-checker", "compliance-officer", "auditor");

  static final List<String> NON_READERS = List.of("integration-maker", "integration-checker", "security-admin");

  private JsonNode body(MvcTestResult r) {
    return json.readTree(r.getResponse().getContentAsByteArray());
  }

  private List<JsonNode> audits() {
    return jdbc.queryForList("select payload::text from outbox where topic = ? order by id", String.class, AUDIT_TOPIC)
        .stream()
        .map(json::readTree)
        .toList();
  }

  // --- roles per endpoint ---------------------------------------------------------------------

  @Test
  void everyReadingRoleMaySearchAndOpenATransaction() {
    for (String role : READERS) {
      RequestPostProcessor op = operator(role + "01", role);
      assertThat(mvc.get().uri(TXNS + "?txnId=" + TXN).with(op).exchange()).as(role).hasStatus(200);
      assertThat(mvc.get().uri(TXNS + "/{id}", TXN).with(op).exchange()).as(role).hasStatus(200);
    }
  }

  @Test
  void rolesWithoutTransactionAccessAndUnknownRolesAreForbiddenAndNothingIsAsked() {
    for (String role : List.of("integration-maker", "integration-checker", "security-admin", "ops-viewer", "org-maker")) {
      RequestPostProcessor op = operator(role + "01", role);
      MvcTestResult search = mvc.get().uri(TXNS).with(op).exchange();
      assertThat(search).as(role).hasStatus(403);
      assertThat(body(search).path("code").asString()).isEqualTo("MW-AUTH-002");
      assertThat(mvc.get().uri(TXNS + "/{id}", TXN).with(op).exchange()).as(role).hasStatus(403);
    }
    assertThat(QUERIES).isEmpty();
  }

  @Test
  void noTokenIs401() {
    MvcTestResult r = mvc.get().uri(TXNS).exchange();
    assertThat(r).hasStatus(401);
    assertThat(body(r).path("code").asString()).isEqualTo("MW-AUTH-001");
    assertThat(mvc.get().uri(TXNS + "/{id}", TXN).exchange()).hasStatus(401);
    assertThat(QUERIES).isEmpty();
  }

  @Test
  void viewerAndAuditorAreReadOnlyOnEveryAdminPath() {
    for (String role : List.of("viewer", "auditor")) {
      RequestPostProcessor op = operator(role + "01", role);
      for (String path : List.of(TXNS, TXNS + "/" + TXN, "/admin/v1/change-requests")) {
        MvcTestResult post = mvc.post().uri(path).with(op).exchange();
        assertThat(post).as(role + " POST " + path).hasStatus(403);
        assertThat(body(post).path("code").asString()).isEqualTo("MW-AUTH-002");
        assertThat(mvc.put().uri(path).with(op).exchange()).as(role + " PUT " + path).hasStatus(403);
        assertThat(mvc.delete().uri(path).with(op).exchange()).as(role + " DELETE " + path).hasStatus(403);
      }
    }
    // a role that may write gets past the read-only rule (and finds no such write endpoint yet)
    assertThat(mvc.post().uri(TXNS).with(operator("maker01", "viewer", "ops-maker")).exchange()).hasStatus(405);
  }

  // --- search, timeline and audit -------------------------------------------------------------

  @Test
  void searchByTxnIdAndByMsisdnFindsTheTxnAndItsDetailHasTheTimelineAndAuditTrail() throws Exception {
    RequestPostProcessor viewer = operator("viewer01", "viewer");

    for (List<String> q : List.of(List.of("txnId", TXN), List.of("msisdn", "+8801711000001"))) {
      MvcTestResult r = mvc.get().uri(TXNS).param(q.get(0), q.get(1)).with(viewer).exchange();
      assertThat(r).as(q.toString()).hasStatus(200);
      JsonNode page = body(r);
      assertThat(page.path("items")).as(q.toString()).hasSize(1);
      assertThat(page.path("items").get(0).path("txnId").asString()).isEqualTo(TXN);
      assertThat(page.path("items").get(0).path("initiator").path("id").asString()).isEqualTo("0171****001");
      assertValid("/admin/v1/transactions", page);
    }
    assertThat(QUERIES).anyMatch(q -> q.contains("msisdn=+8801711000001"));

    MvcTestResult d = mvc.get().uri(TXNS + "/{id}", TXN).with(viewer).exchange();
    assertThat(d).hasStatus(200);
    JsonNode detail = body(d);
    assertThat(detail.path("transaction").path("txnId").asString()).isEqualTo(TXN);
    assertThat(detail.path("timeline").valueStream().map(e -> e.path("kind").asString()).toList())
        .containsExactly("STATE", "CORE", "STATE");
    assertThat(detail.path("timeline").get(2).path("to").asString()).isEqualTo("COMPLETED");
    assertThat(detail.path("partnerCalls")).isEmpty();
    assertThat(detail.path("auditTrail").valueStream().map(e -> e.path("seq").asLong()).toList()).containsExactly(7L, 9L);
    assertThat(detail.path("auditTrail").get(1).path("after").path("status").asString()).isEqualTo("COMPLETED");
    assertThat(detail.path("revealed").asBoolean()).isFalse();
    assertValid("/admin/v1/transactions/{txnId}", detail);
    assertThat(QUERIES).anyMatch(q -> q.startsWith("/internal/v1/audit/events?") && q.contains("txnId=" + TXN));
    assertThat(detail.toString()).doesNotContain(RAW_MSISDN);
  }

  @Test
  void anUnknownTxnIsNotFoundAndBadFiltersAreRefusedBeforeAnythingIsAsked() {
    RequestPostProcessor viewer = operator("viewer01", "viewer");
    MvcTestResult missing = mvc.get().uri(TXNS + "/{id}", MISSING_TXN).with(viewer).exchange();
    assertThat(missing).hasStatus(404);
    assertThat(body(missing).path("code").asString()).isEqualTo("MW-VAL-003");

    QUERIES.clear();
    for (String q : List.of("status=NOPE", "limit=0", "limit=201", "from=yesterday", "txnId=x", "msisdn=abc",
        "from=2026-11-02T00:00:00Z&to=2026-11-01T00:00:00Z")) {
      MvcTestResult r = mvc.get().uri(TXNS + "?" + q).with(viewer).exchange();
      assertThat(r.getResponse().getStatus()).as(q).isEqualTo(400);
    }
    assertThat(mvc.get().uri(TXNS + "/not-a-uuid").with(viewer).exchange()).hasStatus(400);
    assertThat(QUERIES).isEmpty();
  }

  @Test
  void anUnavailableOrchestratorIsARetryableSystemError() {
    orchestratorDown = true;
    MvcTestResult r = mvc.get().uri(TXNS).with(operator("viewer01", "viewer")).exchange();
    assertThat(r).hasStatus(503);
    assertThat(body(r).path("code").asString()).isEqualTo("MW-SYS-040");
    assertThat(body(r).path("retryable").asBoolean()).isTrue();
  }

  // --- reveal ---------------------------------------------------------------------------------

  @Test
  void complianceOfficerAndAuditorSeeTheUnmaskedMsisdnAndEachRevealIsAudited() {
    for (String role : List.of("compliance-officer", "auditor")) {
      jdbc.update("delete from outbox");
      MvcTestResult r = mvc.get().uri(TXNS + "/{id}?reveal=true", TXN).with(operator(role + "01", role)).exchange();

      assertThat(r).as(role).hasStatus(200);
      JsonNode detail = body(r);
      assertThat(detail.path("revealed").asBoolean()).isTrue();
      assertThat(detail.path("transaction").path("initiator").path("id").asString()).isEqualTo(RAW_MSISDN);
      assertThat(detail.path("transaction").path("creditParty").path("id").asString()).isEqualTo("01711000002");
      assertValid("/admin/v1/transactions/{txnId}", detail);

      List<JsonNode> events = audits();
      assertThat(events).as(role).hasSize(1);
      JsonNode e = events.get(0);
      assertThat(e.path("action").asString()).isEqualTo("admin.txn.msisdn.revealed");
      assertThat(e.path("actor").path("type").asString()).isEqualTo("user");
      assertThat(e.path("actor").path("id").asString()).isEqualTo(role + "01");
      assertThat(e.path("resource").asString()).isEqualTo("transaction/" + TXN);
      assertThat(e.path("txnId").asString()).isEqualTo(TXN);
      assertThat(e.path("outcome").asString()).isEqualTo("SUCCESS");
      assertThat(e.path("service").asString()).isEqualTo("mw-admin-api");
      assertThat(e.path("after").path("role").asString()).isEqualTo(role);
      // the audit names what was shown, never the number itself
      assertThat(e.toString()).doesNotContain(RAW_MSISDN).doesNotContain("01711000002");
      assertThat(ContractSchemas.schema("events/mw-audit-dw/mw.audit.event.v1.schema.json").validate(e))
          .as("%s", e)
          .isEmpty();
    }
  }

  @Test
  void otherRolesAskingForARevealGetMaskedValuesAndNothingIsAudited() {
    for (String role : List.of("viewer", "ops-maker", "ops-checker", "ops-emergency", "recon-maker", "recon-checker")) {
      QUERIES.clear();
      MvcTestResult r = mvc.get().uri(TXNS + "/{id}?reveal=true", TXN).with(operator(role + "01", role)).exchange();

      assertThat(r).as(role).hasStatus(200);
      JsonNode detail = body(r);
      assertThat(detail.path("revealed").asBoolean()).isFalse();
      assertThat(detail.path("transaction").path("initiator").path("id").asString()).isEqualTo("0171****001");
      assertThat(detail.toString()).doesNotContain(RAW_MSISDN);
      // the orchestrator is never even asked for unmasked values
      assertThat(QUERIES).noneMatch(q -> q.contains("reveal=true"));
    }
    assertThat(audits()).isEmpty();
  }

  @Test
  void anAllowedRoleThatDoesNotAskForARevealGetsMaskedValuesAndNoAudit() {
    MvcTestResult r = mvc.get().uri(TXNS + "/{id}", TXN).with(operator("compliance01", "compliance-officer")).exchange();
    assertThat(r).hasStatus(200);
    assertThat(body(r).path("revealed").asBoolean()).isFalse();
    assertThat(body(r).path("transaction").path("initiator").path("id").asString()).isEqualTo("0171****001");
    assertThat(QUERIES).noneMatch(q -> q.contains("reveal=true"));
    assertThat(audits()).isEmpty();
  }

  private static void assertValid(String path, JsonNode body) {
    Schema schema = mfs.mw.admin.api.contracts.OpenApiSchemas.response(
        "api/mw-admin-api/admin.v1.openapi.yaml", path, "get", "200");
    assertThat(schema.validate(body)).as("%s", body).isEmpty();
  }
}
