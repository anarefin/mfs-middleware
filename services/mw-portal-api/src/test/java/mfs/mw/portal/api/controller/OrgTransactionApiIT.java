package mfs.mw.portal.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import mfs.mw.portal.api.TestInfrastructure;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
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
 * The SP transaction query (ticket 35, WP22): the org portal asks mw-orchestrator's internal query
 * API (here a stub HTTP server speaking {@code txn-query.v1}), never its schema, and always for the
 * token's own org, so an SP sees only its own transactions.
 */
@SpringBootTest(properties = "mw.outbox.relay.enabled=false")
@AutoConfigureMockMvc
@Import(TestInfrastructure.class)
class OrgTransactionApiIT {

  static final String TXNS = "/portal/org/v1/transactions";
  static final String OWN_TXN = "01933b8e-7c1a-7b2e-9f3d-2a4c5e6f7a8b";
  static final String OTHER_TXN = "01933b8e-7c1a-7b2e-9f3d-2a4c5e6f7a8c";

  /** What the orchestrator stub answers; masked as the real one masks (contract txn-query.v1). */
  static final String TXN_JSON =
      """
      {"role":"INITIATOR","txnId":"%s","txnType":"BULK_DISBURSE","channel":"ORG_PORTAL","status":"COMPLETED",
       "createdAt":"2026-11-03T08:15:30Z","updatedAt":"2026-11-03T08:15:32Z","correlationId":"bulk-1",
       "amount":{"valueMinor":1000,"currency":"BDT"},
       "initiator":{"type":"ORG","id":"****G001"},"debitParty":{"type":"ORG","id":"0131****001"},
       "creditParty":{"type":"WALLET","id":"0171****003","walletType":"CUSTOMER"},
       "attributes":{"parentTxnId":"01933b8e-0000-7000-8000-000000000001","lineNo":"2"}}
      """
          .formatted(OWN_TXN);

  static final List<String> QUERIES = new CopyOnWriteArrayList<>();
  static volatile boolean orchestratorDown;
  static final HttpServer ORCHESTRATOR = startStub();

  static HttpServer startStub() {
    try {
      HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      s.createContext("/internal/v1/transactions", OrgTransactionApiIT::answer);
      s.start();
      return s;
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  static void answer(HttpExchange ex) throws IOException {
    String path = ex.getRequestURI().getPath();
    String query = ex.getRequestURI().getRawQuery() == null ? "" : ex.getRequestURI().getRawQuery();
    QUERIES.add(path + "?" + query);
    if (orchestratorDown) {
      respond(ex, 500, "{\"code\":\"MW-SYS-001\",\"message\":\"Internal error\",\"correlationId\":\"c\",\"retryable\":true,\"details\":[]}");
    } else if (path.endsWith("/transactions")) {
      respond(ex, 200, "{\"items\":[" + TXN_JSON + "],\"limit\":50,\"offset\":0}");
    } else if (path.endsWith(OWN_TXN) && query.contains("orgId=ORG001")) {
      respond(
          ex,
          200,
          "{\"transaction\":" + TXN_JSON + ",\"timeline\":["
              + "{\"kind\":\"STATE\",\"from\":\"RECEIVED\",\"to\":\"VALIDATING\",\"trigger\":\"ValidationStarted\",\"at\":\"2026-11-03T08:15:30Z\"},"
              + "{\"kind\":\"CORE\",\"command\":\"ValidateTransaction\",\"commandId\":\"x:validate\",\"sends\":1,\"result\":\"TransactionValidated\",\"outcome\":\"SUCCESS\",\"at\":\"2026-11-03T08:15:30Z\",\"resultAt\":\"2026-11-03T08:15:31Z\"},"
              + "{\"kind\":\"STATE\",\"from\":\"VALIDATING\",\"to\":\"POSTING\",\"trigger\":\"Validated\",\"at\":\"2026-11-03T08:15:31Z\"},"
              + "{\"from\":\"POSTING\",\"to\":\"COMPLETED\",\"trigger\":\"Posted\",\"at\":\"2026-11-03T08:15:32Z\"}],"
              + "\"partnerCalls\":[]}");
    } else {
      respond(ex, 404, "{\"code\":\"MW-VAL-003\",\"message\":\"Transaction not found\",\"correlationId\":\"c\",\"retryable\":false,\"details\":[]}");
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
    registry.add(
        "mw.portal-api.orchestrator.base-url", () -> "http://127.0.0.1:" + ORCHESTRATOR.getAddress().getPort());
  }

  private final MockMvcTester mvc;
  private final JdbcTemplate jdbc;
  private final JsonMapper json;

  @Autowired
  OrgTransactionApiIT(MockMvcTester mvc, JdbcTemplate jdbc, JsonMapper json) {
    this.mvc = mvc;
    this.jdbc = jdbc;
    this.json = json;
  }

  @BeforeEach
  void reset() {
    QUERIES.clear();
    orchestratorDown = false;
    jdbc.update("delete from batch_item");
    jdbc.update("delete from batch");
  }

  static RequestPostProcessor user(String sub, String org, String... roles) {
    return jwt()
        .jwt(
            j ->
                j.subject(sub)
                    .claim("preferred_username", sub + "-name")
                    .claim("orgId", org)
                    .claim("realm_access", Map.of("roles", List.of(roles))));
  }

  static final RequestPostProcessor MAKER = user("maker-1", "ORG001", "org-maker");
  static final RequestPostProcessor CHECKER = user("checker-1", "ORG001", "org-checker");
  static final RequestPostProcessor NO_ROLE = user("nobody-1", "ORG001");
  static final RequestPostProcessor OTHER_ORG = user("checker-2", "ORG002", "org-checker");

  private JsonNode body(MvcTestResult r) {
    return json.readTree(r.getResponse().getContentAsByteArray());
  }

  @Test
  void anSpListsItsOwnTransactionsAndTheOrgComesFromTheToken() {
    MvcTestResult r =
        mvc.get().uri(TXNS + "?status=COMPLETED&orgId=ORG002&from=2026-11-01T00:00:00Z").with(CHECKER).exchange();

    assertThat(r).hasStatus(200);
    JsonNode page = body(r);
    assertThat(page.path("items")).hasSize(1);
    JsonNode item = page.path("items").get(0);
    assertThat(item.path("txnId").asString()).isEqualTo(OWN_TXN);
    assertThat(item.path("status").asString()).isEqualTo("COMPLETED");
    assertThat(item.path("role").asString()).isEqualTo("INITIATOR");
    assertThat(item.path("creditParty").path("id").asString()).isEqualTo("0171****003");
    assertThat(item.path("attributes").path("parentTxnId").asString())
        .isEqualTo("01933b8e-0000-7000-8000-000000000001");
    // the SP view carries no internals of the middleware
    assertThat(item.has("correlationId")).isFalse();
    assertThat(item.has("channel")).isFalse();
    assertThat(item.has("initiator")).isFalse();

    assertThat(QUERIES).hasSize(1);
    String sent = QUERIES.get(0);
    assertThat(sent).contains("orgId=ORG001").doesNotContain("ORG002");
    assertThat(sent).contains("status=COMPLETED").contains("from=2026-11-01T00%3A00%3A00Z");
  }

  @Test
  void anSpSeesTheTimelineOfItsOwnTransactionOnly() {
    MvcTestResult own = mvc.get().uri(TXNS + "/{id}", OWN_TXN).with(MAKER).exchange();
    assertThat(own).hasStatus(200);
    JsonNode detail = body(own);
    assertThat(detail.path("transaction").path("txnId").asString()).isEqualTo(OWN_TXN);
    assertThat(detail.path("timeline").valueStream().map(s -> s.path("to").asString()).toList())
        .containsExactly("VALIDATING", "POSTING", "COMPLETED");
    // the SP sees state changes only: the middleware's core commands stay internal
    assertThat(detail.path("timeline").toString()).doesNotContain("ValidateTransaction").doesNotContain("commandId");
    assertThat(detail.path("transaction").path("role").asString()).isEqualTo("INITIATOR");
    assertThat(detail.has("partnerCalls")).isFalse();
    assertThat(QUERIES.get(0)).contains("orgId=ORG001");

    // another org's token: the orchestrator is asked for ORG002 and finds nothing
    MvcTestResult other = mvc.get().uri(TXNS + "/{id}", OWN_TXN).with(OTHER_ORG).exchange();
    assertThat(other).hasStatus(404);
    assertThat(body(other).path("code").asString()).isEqualTo("MW-VAL-003");
    assertThat(QUERIES.get(1)).contains("orgId=ORG002");

    assertThat(mvc.get().uri(TXNS + "/{id}", OTHER_TXN).with(MAKER).exchange()).hasStatus(404);
    assertThat(mvc.get().uri(TXNS + "/{id}", "not-a-uuid").with(MAKER).exchange()).hasStatus(400);
  }

  @Test
  void aBatchFilterAsksForThatBatchsChildrenAndAnotherOrgsBatchIsNotFound() {
    MvcTestResult up =
        mvc.post()
            .uri("/portal/org/v1/bulk/batches")
            .with(MAKER)
            .contentType(MediaType.parseMediaType("text/csv"))
            .content("msisdn,amount,reference\n01711000003,10,R1\n".getBytes(StandardCharsets.UTF_8))
            .exchange();
    String batchId = body(up).path("batchId").asString();
    MvcTestResult approved = mvc.post().uri("/portal/org/v1/bulk/batches/{id}/approve", batchId).with(CHECKER).exchange();
    String parent = body(approved).path("parentTxnId").asString();

    assertThat(mvc.get().uri(TXNS + "?batchId=" + batchId).with(CHECKER).exchange()).hasStatus(200);
    assertThat(QUERIES.get(0)).contains("orgId=ORG001").contains("parentTxnId=" + parent);

    MvcTestResult foreign = mvc.get().uri(TXNS + "?batchId=" + batchId).with(OTHER_ORG).exchange();
    assertThat(foreign).hasStatus(404);
    assertThat(QUERIES).hasSize(1);
  }

  @Test
  void aUserWithoutAPortalRoleIsForbiddenAndNothingIsAsked() {
    MvcTestResult r = mvc.get().uri(TXNS).with(NO_ROLE).exchange();
    assertThat(r).hasStatus(403);
    assertThat(body(r).path("code").asString()).isEqualTo("MW-AUTH-002");
    assertThat(mvc.get().uri(TXNS).exchange()).hasStatus(401);
    assertThat(QUERIES).isEmpty();
  }

  @Test
  void badFiltersAreRefusedBeforeTheOrchestratorIsAsked() {
    for (String q : List.of("status=NOPE", "limit=0", "limit=201", "from=yesterday", "batchId=x")) {
      MvcTestResult r = mvc.get().uri(TXNS + "?" + q).with(CHECKER).exchange();
      assertThat(r.getResponse().getStatus()).as(q).isIn(400, 404);
    }
    assertThat(QUERIES).isEmpty();
  }

  @Test
  void anUnavailableOrchestratorIsARetryableSystemError() {
    orchestratorDown = true;

    MvcTestResult r = mvc.get().uri(TXNS).with(CHECKER).exchange();

    assertThat(r).hasStatus(503);
    JsonNode e = body(r);
    assertThat(e.path("code").asString()).isEqualTo("MW-SYS-040");
    assertThat(e.path("retryable").asBoolean()).isTrue();
  }
}
