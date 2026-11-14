package mfs.mw.admin.api.controller;

import static mfs.mw.admin.api.controller.WalletUnlockApiIT.operator;
import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.Schema;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import mfs.mw.admin.api.TestInfrastructure;
import mfs.mw.admin.api.contracts.ContractSchemas;
import mfs.mw.admin.api.contracts.OpenApiSchemas;
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
 * The care PIN reset of ticket 70 through the maker-checker engine: an ops-maker asks (resourceType
 * {@code pin_reset}), a different ops-checker approves; the approval asks mw-auth (here a stub of
 * {@code POST /internal/v1/pin-resets}, validated against {@code auth-internal.v1}) to open the reset
 * with the services token, idempotent on the change request id; mw-auth down leaves the request
 * PENDING. A pending unlock of the same wallet does not block it.
 */
@SpringBootTest(properties = "mw.outbox.relay.enabled=false")
@AutoConfigureMockMvc
@Import(TestInfrastructure.class)
class PinResetApiIT {

  static final String WALLET = "01711000321";
  static final String TOKEN = "service-token-70";
  static final String AUTH_CONTRACT = "api/mw-auth/auth-internal.v1.openapi.yaml";
  static final List<String> CALLS = new CopyOnWriteArrayList<>();
  static volatile int authStatus = 201;
  static final HttpServer AUTH = startStub();

  static HttpServer startStub() {
    try {
      HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      s.createContext("/internal/v1/pin-resets", PinResetApiIT::pinResets);
      s.createContext("/token", ex -> respond(ex, 200, "{\"access_token\":\"" + TOKEN + "\",\"expires_in\":300}"));
      s.start();
      return s;
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  static void pinResets(HttpExchange ex) throws IOException {
    String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    CALLS.add(ex.getRequestHeaders().getFirst("Authorization") + " " + body);
    if (authStatus >= 300) {
      respond(ex, authStatus, "{\"code\":\"MW-SYS-001\",\"message\":\"x\",\"correlationId\":\"c\",\"retryable\":true,\"details\":[]}");
    } else {
      respond(ex, authStatus,
          "{\"resetId\":\"3f6b1c2d-4e5f-4a6b-9c7d-8e9f0a1b2c3d\",\"status\":\"APPROVED\",\"expiresAt\":\"2026-11-17T10:00:00Z\"}");
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
    AUTH.stop(0);
  }

  @DynamicPropertySource
  static void upstreams(DynamicPropertyRegistry registry) {
    String base = "http://127.0.0.1:" + AUTH.getAddress().getPort();
    registry.add("mw.admin-api.auth.base-url", () -> base);
    registry.add("mw.admin-api.internal-auth.token-uri", () -> base + "/token");
  }

  static final RequestPostProcessor MAKER = operator("ops-maker01", "viewer", "ops-maker");
  static final RequestPostProcessor CHECKER = operator("ops-checker01", "viewer", "ops-checker");

  private final MockMvcTester mvc;
  private final JdbcTemplate jdbc;
  private final JsonMapper json;

  @Autowired
  PinResetApiIT(MockMvcTester mvc, JdbcTemplate jdbc, JsonMapper json) {
    this.mvc = mvc;
    this.jdbc = jdbc;
    this.json = json;
  }

  @BeforeEach
  void reset() {
    jdbc.update("delete from outbox");
    jdbc.update("delete from content_version");
    jdbc.update("delete from change_request");
    CALLS.clear();
    authStatus = 201;
  }

  private JsonNode body(MvcTestResult r) {
    return json.readTree(r.getResponse().getContentAsByteArray());
  }

  private MvcTestResult ask(RequestPostProcessor who, String action) {
    return mvc.post().uri("/admin/v1/wallets/{id}/change-requests", WALLET).with(who)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"action\":\"" + action + "\",\"reason\":\"lost phone, identity checked at branch\"}").exchange();
  }

  private MvcTestResult approve(RequestPostProcessor who, String id) {
    return mvc.post().uri("/admin/v1/change-requests/{id}/approve", id).with(who).exchange();
  }

  private List<JsonNode> audits(String action) {
    return jdbc.queryForList("select payload::text from outbox where topic = 'mw.audit.event.v1' order by id", String.class)
        .stream().map(json::readTree).filter(e -> e.path("action").asString().equals(action)).toList();
  }

  @Test
  void anApprovedPinResetAsksMwAuthOnceAndIsAudited() {
    MvcTestResult created = ask(MAKER, "PIN_RESET");
    assertThat(created).hasStatus(201);
    JsonNode cr = body(created);
    Schema view = OpenApiSchemas.response("api/mw-admin-api/admin.v1.openapi.yaml",
        "/admin/v1/wallets/{walletId}/change-requests", "post", "201");
    assertThat(view.validate(cr)).as("%s", cr).isEmpty();
    assertThat(cr.path("resourceType").asString()).isEqualTo("pin_reset");
    assertThat(cr.path("resourceId").asString()).isEqualTo("0171****321");
    assertThat(cr.path("diff").path("after").path("status").asString()).isEqualTo("RESET");
    assertThat(CALLS).as("nothing before the approval").isEmpty();
    assertThat(ask(MAKER, "UNLOCK")).as("an unlock of the same wallet is another request").hasStatus(201);
    assertThat(ask(MAKER, "PIN_RESET")).as("one open reset request per wallet").hasStatus(409);

    String id = cr.path("id").asString();
    MvcTestResult approved = approve(CHECKER, id);
    assertThat(approved).hasStatus(200);
    assertThat(body(approved).path("status").asString()).isEqualTo("APPROVED");
    assertThat(body(approved).path("payload").path("resetId").asString()).isEqualTo("3f6b1c2d-4e5f-4a6b-9c7d-8e9f0a1b2c3d");

    assertThat(CALLS).hasSize(1);
    String call = CALLS.getFirst();
    assertThat(call).startsWith("Bearer " + TOKEN + " ");
    JsonNode sent = json.readTree(call.substring(call.indexOf('{')));
    Schema request = OpenApiSchemas.component(AUTH_CONTRACT, "CarePinResetRequest");
    assertThat(request.validate(sent)).as("%s", sent).isEmpty();
    assertThat(sent.path("walletId").asString()).isEqualTo(WALLET);
    assertThat(sent.path("changeRequestId").asString()).isEqualTo(id);
    assertThat(sent.path("makerId").asString()).isEqualTo("ops-maker01");
    assertThat(sent.path("checkerId").asString()).isEqualTo("ops-checker01");

    JsonNode audit = audits("admin.change_request.approved").getFirst();
    assertThat(ContractSchemas.schema("events/mw-audit-dw/mw.audit.event.v1.schema.json").validate(audit)).isEmpty();
    assertThat(audit.path("resource").asString()).isEqualTo("pin_reset/0171****321");
    assertThat(audit.path("after").path("sentAs").asString()).isEqualTo("mw-auth:/internal/v1/pin-resets");
    assertThat(audit.toString()).doesNotContain(WALLET);
    assertThat(jdbc.queryForObject("select count(*) from outbox where topic in ('auth.wallet-lock.v1', 'core.wallet.command.v1')",
        Integer.class)).as("a reset sends no lock command").isZero();
  }

  @Test
  void mwAuthDownLeavesTheRequestPendingAndARetrySucceeds() {
    String id = body(ask(MAKER, "PIN_RESET")).path("id").asString();
    authStatus = 503;
    MvcTestResult down = approve(CHECKER, id);
    assertThat(down).hasStatus(503);
    assertThat(body(down).path("code").asString()).isEqualTo("MW-SYS-040");
    assertThat(jdbc.queryForObject("select status from change_request where id = ?::uuid", String.class, id))
        .isEqualTo("PENDING");
    assertThat(audits("admin.change_request.approved")).isEmpty();

    authStatus = 200;
    assertThat(approve(CHECKER, id)).hasStatus(200);
    assertThat(CALLS).hasSize(2);
    assertThat(CALLS.get(0).substring(CALLS.get(0).indexOf('{'))).isEqualTo(CALLS.get(1).substring(CALLS.get(1).indexOf('{')));
  }

  @Test
  void theMakerCannotApproveOwnResetAndOnlyOpsRolesDecide() {
    RequestPostProcessor both = operator("ops-both01", "ops-maker", "ops-checker");
    String id = body(ask(both, "PIN_RESET")).path("id").asString();
    MvcTestResult own = approve(both, id);
    assertThat(own).hasStatus(403);
    assertThat(body(own).path("code").asString()).isEqualTo("MW-AUTH-030");
    assertThat(approve(operator("int-checker01", "integration-checker"), id)).hasStatus(403);
    assertThat(CALLS).isEmpty();
    MvcTestResult list = mvc.get().uri("/admin/v1/change-requests?resourceType=pin_reset").with(CHECKER).exchange();
    assertThat(list).hasStatus(200);
    assertThat(body(list).path("items").size()).isEqualTo(1);
  }
}
