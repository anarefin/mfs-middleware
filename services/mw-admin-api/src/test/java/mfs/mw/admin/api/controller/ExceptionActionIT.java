package mfs.mw.admin.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import mfs.mw.admin.api.MutableClock;
import mfs.mw.admin.api.TestInfrastructure;
import mfs.mw.admin.api.contracts.ContractSchemas;
import mfs.mw.admin.api.contracts.OpenApiSchemas;
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
 * Ticket 127b (UC-22): an action on a reconciliation exception is a {@code recon_exception} change request. A
 * recon-maker asks; nothing goes to mw-orchestrator or mw-recon before a different recon-checker approves; the
 * approval sends the ticket 37 message (FORCE_COMMIT on mw.txn.action.v1, REVERSE as a REVERSAL on
 * mw.txn.request.v1) through the outbox and then tells mw-recon (ACTIONED there; RESOLVED by the outcome is
 * mw-recon's {@code ExceptionActionIT}). Stubs: mw-orchestrator's txn-query and mw-recon's work queue.
 */
@SpringBootTest(properties = "mw.outbox.relay.enabled=false")
@AutoConfigureMockMvc
@Import({TestInfrastructure.class, ExceptionActionIT.TestClock.class})
class ExceptionActionIT {

  static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");
  static final String TXN = "01933b8e-7c1a-7b2e-9f3d-2a4c5e6f7b01";
  static final String EXC = "0196a1b2-0000-7000-8000-00000127b001";
  static final String CONTRACT = "api/mw-admin-api/admin.v1.openapi.yaml";

  @TestConfiguration(proxyBeanMethods = false)
  static class TestClock {
    @Bean
    @Primary
    Clock testClock() {
      return new MutableClock(NOW);
    }
  }

  static final Map<String, String> TXN_STATUS = new ConcurrentHashMap<>();
  static final Map<String, String> EXC_STATE = new ConcurrentHashMap<>();
  /** Bodies and operator headers mw-recon received on POST .../actions. */
  static final List<String> RECORDED = new CopyOnWriteArrayList<>();
  static final HttpServer UPSTREAM = startStub();

  static HttpServer startStub() {
    try {
      HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      s.createContext("/internal/v1/transactions", ExceptionActionIT::transactions);
      s.createContext("/internal/v1/recon/exceptions", ExceptionActionIT::recon);
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
    String status = TXN_STATUS.get(id);
    if (status == null) {
      respond(ex, 404, "{}");
      return;
    }
    String txn = """
        {"txnId":"%s","txnType":"BILL_PAY","channel":"CUSTOMER_APP","status":"%s",
         "createdAt":"2026-11-19T08:00:00Z","updatedAt":"2026-11-19T08:01:00Z","correlationId":"corr-1",
         "amount":{"valueMinor":5000,"currency":"BDT"},"fee":{"valueMinor":0,"currency":"BDT"},
         "initiator":{"type":"MSISDN","id":"0171****001"},
         "debitParty":{"type":"WALLET","id":"0171****001"},"creditParty":{"type":"ORG","id":"billagg"}}
        """.formatted(id, status);
    respond(ex, 200, "{\"transaction\":" + txn + ",\"timeline\":[],\"partnerCalls\":[]}");
  }

  static void recon(HttpExchange ex) throws IOException {
    String path = ex.getRequestURI().getPath();
    if ("POST".equals(ex.getRequestMethod()) && path.endsWith("/actions")) {
      // the services token is sent (mw-recon accepts only mw-admin-api's, ticket 127b security review)
      if (!"Bearer t".equals(ex.getRequestHeaders().getFirst("Authorization"))) {
        respond(ex, 401, "{}");
        return;
      }
      RECORDED.add(ex.getRequestHeaders().getFirst("X-Mw-Operator-Id") + " "
          + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      respond(ex, 200, "{}");
      return;
    }
    String id = path.substring(path.lastIndexOf('/') + 1);
    String state = EXC_STATE.get(id);
    if (state == null) {
      respond(ex, 404, "{}");
      return;
    }
    respond(ex, 200, """
        {"id":"%s","reconClass":"STATUS_MISMATCH","detail":"LEDGER_MISSING","owner":"ops","partnerId":"billagg",
         "txnId":"%s","state":"%s","createdAt":"2026-11-19T20:00:00Z","ageHours":14,"suggestedAction":"FORCE_COMPLETE"}
        """.formatted(id, TXN, state));
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
    registry.add("mw.recon.base-url", () -> base);
    registry.add("mw.admin-api.internal-auth.token-uri", () -> base + "/token");
  }

  private final MockMvcTester mvc;
  private final JdbcTemplate jdbc;
  private final JsonMapper json;

  @Autowired
  ExceptionActionIT(MockMvcTester mvc, JdbcTemplate jdbc, JsonMapper json) {
    this.mvc = mvc;
    this.jdbc = jdbc;
    this.json = json;
  }

  @BeforeEach
  void reset() {
    TXN_STATUS.clear();
    EXC_STATE.clear();
    RECORDED.clear();
    jdbc.update("delete from outbox");
    jdbc.update("delete from change_request");
  }

  static RequestPostProcessor operator(String username, String... roles) {
    return jwt().jwt(j -> j.subject("sub-" + username)
        .issuer("http://keycloak:8080/realms/ops")
        .claim("preferred_username", username)
        .claim("realm_access", Map.of("roles", List.of(roles))));
  }

  static final RequestPostProcessor MAKER = operator("recon-maker01", "recon-maker", "recon-checker");
  static final RequestPostProcessor CHECKER = operator("recon-checker01", "recon-checker");
  static final RequestPostProcessor OPS_CHECKER = operator("ops-checker01", "ops-checker");
  static final RequestPostProcessor OPS_MAKER = operator("ops-maker01", "ops-maker");

  private JsonNode body(MvcTestResult r) {
    return json.readTree(r.getResponse().getContentAsByteArray());
  }

  private MvcTestResult ask(RequestPostProcessor who, String action) {
    return mvc.post().uri("/admin/v1/recon-exceptions/{id}/change-requests", EXC).with(who)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"action\":\"" + action + "\",\"reason\":\"ledger posting missing, biller confirmed\"}").exchange();
  }

  private String asked(String action) {
    MvcTestResult r = ask(MAKER, action);
    assertThat(r).hasStatus(201);
    JsonNode cr = body(r);
    assertThat(OpenApiSchemas.response(CONTRACT, "/admin/v1/recon-exceptions/{exceptionId}/change-requests", "post",
        "201").validate(cr)).as("%s", cr).isEmpty();
    assertThat(cr.path("resourceType").asString()).isEqualTo("recon_exception");
    assertThat(cr.path("payload").path("txnId").asString()).isEqualTo(TXN);
    return cr.path("id").asString();
  }

  private MvcTestResult approve(RequestPostProcessor who, String id) {
    return mvc.post().uri("/admin/v1/change-requests/{id}/approve", id).with(who).exchange();
  }

  private List<JsonNode> outbox(String topic) {
    return jdbc.queryForList("select payload::text from outbox where topic = ? order by id", String.class, topic)
        .stream().map(json::readTree).toList();
  }

  private void assertNothingSent() {
    assertThat(outbox("mw.txn.action.v1")).isEmpty();
    assertThat(outbox("mw.txn.request.v1")).isEmpty();
    assertThat(RECORDED).isEmpty();
  }

  @Test
  void aForceCommitMovesNothingBeforeApprovalThenGoesToTheOrchestratorAndMwRecon() {
    TXN_STATUS.put(TXN, "MANUAL_REVIEW");
    EXC_STATE.put(EXC, "EXPLAINED");
    String id = asked("FORCE_COMMIT");
    assertNothingSent();

    // maker != checker (MW-AUTH-030) even with the checker role; ops-checker is not a recon checker
    MvcTestResult own = approve(MAKER, id);
    assertThat(own).hasStatus(403);
    assertThat(body(own).path("code").asString()).isEqualTo("MW-AUTH-030");
    assertThat(approve(OPS_CHECKER, id)).hasStatus(403);
    assertNothingSent();

    MvcTestResult ok = approve(CHECKER, id);
    assertThat(ok).hasStatus(200);
    assertThat(body(ok).path("status").asString()).isEqualTo("APPROVED");
    List<JsonNode> actions = outbox("mw.txn.action.v1");
    assertThat(actions).singleElement().satisfies(a -> {
      assertThat(ContractSchemas.schema("events/mw-admin-api/mw.txn.action.v1.schema.json").validate(a)).isEmpty();
      assertThat(a.path("txnId").asString()).isEqualTo(TXN);
      assertThat(a.path("action").asString()).isEqualTo("FORCE_COMMIT");
      assertThat(a.path("changeRequestId").asString()).isEqualTo(id);
      assertThat(a.path("checkerId").asString()).isEqualTo("recon-checker01");
    });
    assertThat(RECORDED).singleElement().satisfies(r -> {
      assertThat(r).startsWith("recon-checker01 ");
      assertThat(r).contains("\"action\":\"FORCE_COMMIT\"").contains("\"changeRequestId\":\"" + id + "\"");
    });
    List<JsonNode> audits = outbox("mw.audit.event.v1");
    assertThat(audits).extracting(a -> a.path("action").asString())
        .containsExactly("admin.change_request.created", "admin.change_request.approved");
    for (JsonNode a : audits) {
      assertThat(ContractSchemas.schema("events/mw-audit-dw/mw.audit.event.v1.schema.json").validate(a)).isEmpty();
      assertThat(a.path("resource").asString()).isEqualTo("recon_exception/" + EXC);
    }
  }

  @Test
  void aReverseIsAReversalOfTheExceptionsTransaction() {
    TXN_STATUS.put(TXN, "COMPLETED");
    EXC_STATE.put(EXC, "OPEN");
    String id = asked("REVERSE");

    assertThat(approve(CHECKER, id)).hasStatus(200);
    assertThat(outbox("mw.txn.request.v1")).singleElement().satisfies(r -> {
      assertThat(ContractSchemas.schema("events/mw-orchestrator/mw.txn.request.v1.schema.json").validate(r)).isEmpty();
      assertThat(r.path("txnType").asString()).isEqualTo("REVERSAL");
      assertThat(r.path("originalTxnId").asString()).isEqualTo(TXN);
      assertThat(r.path("idempotencyKey").asString()).isEqualTo(id);
    });
    assertThat(RECORDED).singleElement().satisfies(r -> assertThat(r).contains("\"action\":\"REVERSE\""));
  }

  @Test
  void aRejectSendsNothingAndAClosedOrMovedOnRequestIsRefused() {
    TXN_STATUS.put(TXN, "MANUAL_REVIEW");
    EXC_STATE.put(EXC, "OPEN");
    String id = asked("FORCE_COMMIT");
    MvcTestResult rejected = mvc.post().uri("/admin/v1/change-requests/{id}/reject", id).with(CHECKER)
        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"not ours\"}").exchange();
    assertThat(rejected).hasStatus(200);
    assertNothingSent();

    // the exception was resolved meanwhile: the approval is refused and the request stays PENDING
    String again = asked("FORCE_COMMIT");
    EXC_STATE.put(EXC, "RESOLVED");
    MvcTestResult refused = approve(CHECKER, again);
    assertThat(refused).hasStatus(409);
    assertThat(body(refused).path("code").asString()).isEqualTo("MW-VAL-030");
    assertNothingSent();

    // an action that does not fit the transaction, the wrong role, an unknown exception
    EXC_STATE.put(EXC, "OPEN");
    jdbc.update("delete from change_request");
    assertThat(ask(MAKER, "REVERSE")).hasStatus(409);
    assertThat(ask(OPS_MAKER, "FORCE_COMMIT")).hasStatus(403);
    EXC_STATE.clear();
    assertThat(ask(MAKER, "FORCE_COMMIT")).hasStatus(404);
  }
}
