package mfs.mw.admin.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import com.networknt.schema.Schema;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import mfs.mw.admin.api.MutableClock;
import mfs.mw.admin.api.TestInfrastructure;
import mfs.mw.admin.api.client.CoreAccountClient;
import mfs.mw.admin.api.contracts.ContractSchemas;
import mfs.mw.admin.api.contracts.OpenApiSchemas;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Ops unlock of a PIN-locked wallet (ticket 52): an ops-maker asks, a different ops-checker
 * approves; the approval writes (ticket 70) one UNLOCK on {@code auth.wallet-lock.v1} for mw-auth through
 * the outbox, in the same transaction as the status change and its audit, and only then (ticket 95b, ADR
 * D31) lifts the core's lock over REST, once, under the request's unlockTxnId. Request and decision are
 * audited; the MSISDN never leaves masked-free in the view or the audit.
 */
@SpringBootTest(properties = "mw.outbox.relay.enabled=false")
@AutoConfigureMockMvc
@Import({TestInfrastructure.class, WalletUnlockApiIT.TestClock.class})
class WalletUnlockApiIT {

  static final Instant NOW = Instant.parse("2026-11-06T10:00:00Z");
  static final String WALLET = "01711000123";
  static final String CONTRACT = "api/mw-admin-api/admin.v1.openapi.yaml";
  static final String CORE_UNLOCK = "core-wallet.v1:unlock";
  static final String LOCK_TOPIC = "auth.wallet-lock.v1";

  @TestConfiguration(proxyBeanMethods = false)
  static class TestClock {
    @Bean
    @Primary
    Clock testClock() {
      return new MutableClock(NOW);
    }
  }

  @MockitoBean CoreAccountClient core;

  private final MockMvcTester mvc;
  private final JdbcTemplate jdbc;
  private final JsonMapper json;

  @Autowired
  WalletUnlockApiIT(MockMvcTester mvc, JdbcTemplate jdbc, JsonMapper json) {
    this.mvc = mvc;
    this.jdbc = jdbc;
    this.json = json;
  }

  @BeforeEach
  void reset() {
    jdbc.update("delete from outbox");
    jdbc.update("delete from content_version");
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

  private MvcTestResult ask(RequestPostProcessor who, String walletId, String body) {
    return mvc.post().uri("/admin/v1/wallets/{id}/change-requests", walletId).with(who)
        .contentType(MediaType.APPLICATION_JSON).content(body).exchange();
  }

  private String asked() {
    MvcTestResult r = ask(MAKER, WALLET, "{\"action\":\"UNLOCK\",\"reason\":\"customer verified at branch\"}");
    assertThat(r).hasStatus(201);
    JsonNode cr = body(r);
    assertValid("/admin/v1/wallets/{walletId}/change-requests", "post", "201", cr);
    return cr.path("id").asString();
  }

  private MvcTestResult approve(RequestPostProcessor who, String id) {
    return mvc.post().uri("/admin/v1/change-requests/{id}/approve", id).with(who).exchange();
  }

  private MvcTestResult reject(RequestPostProcessor who, String id) {
    return mvc.post().uri("/admin/v1/change-requests/{id}/reject", id).with(who)
        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"not verified\"}").exchange();
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

  @Test
  void anApprovedUnlockSendsUnlockWalletExactlyOnceAndIsAudited() {
    when(core.unlock(any(), any(), any(), any(), any()))
        .thenReturn(new CoreAccountClient.UnlockAnswer(CoreAccountClient.Outcome.UNLOCKED, null));
    String id = asked();
    verifyNoInteractions(core);
    JsonNode created = audits("admin.change_request.created").getFirst();
    assertEvent("events/mw-audit-dw/mw.audit.event.v1.schema.json", created);
    assertThat(created.path("actor").path("id").asString()).isEqualTo("ops-maker01");
    assertThat(created.path("resource").asString()).isEqualTo("wallet/0171****123");

    MvcTestResult r = approve(CHECKER, id);

    assertThat(r).hasStatus(200);
    JsonNode cr = body(r);
    assertValid("/admin/v1/change-requests/{id}/approve", "post", "200", cr);
    assertThat(cr.path("status").asString()).isEqualTo("APPROVED");
    assertThat(cr.path("resourceType").asString()).isEqualTo("wallet");
    assertThat(cr.path("resourceId").asString()).isEqualTo("0171****123");
    assertThat(cr.path("action").asString()).isEqualTo("UNLOCK");

    String unlockTxnId = cr.path("payload").path("unlockTxnId").asString();
    verify(core, times(1)).unlock(WALLET, unlockTxnId, id, "ops-maker01", "ops-checker01");

    JsonNode approved = audits("admin.change_request.approved").getFirst();
    assertEvent("events/mw-audit-dw/mw.audit.event.v1.schema.json", approved);
    assertThat(approved.path("actor").path("id").asString()).isEqualTo("ops-checker01");
    assertThat(approved.path("txnId").asString()).isEqualTo(unlockTxnId);
    assertThat(approved.path("after").path("sentAs").asString()).isEqualTo(LOCK_TOPIC + "," + CORE_UNLOCK);

    // ticket 70: mw-auth lifts its own PIN lock (and any ADMIN lock) on auth.wallet-lock.v1
    List<JsonNode> locks = outbox(LOCK_TOPIC);
    assertThat(locks).hasSize(1);
    JsonNode lock = locks.getFirst();
    assertEvent("events/mw-auth/auth.wallet-lock.v1.schema.json", lock);
    assertThat(lock.path("action").asString()).isEqualTo("UNLOCK");
    assertThat(lock.path("walletId").asString()).isEqualTo(WALLET);
    assertThat(lock.path("commandId").asString()).isEqualTo(id + ":auth-unlock");
    assertThat(lock.path("actor").path("id").asString()).isEqualTo("ops-checker01");
    assertThat(lock.path("changeRequestId").asString()).isEqualTo(id);
    assertThat(jdbc.queryForObject("select msg_key from outbox where topic = ?", String.class, LOCK_TOPIC))
        .isEqualTo(WALLET);
    assertThat(approved.path("before").path("walletStatus").asString()).isEqualTo("LOCKED");
    assertThat(approved.toString()).doesNotContain(WALLET);
    assertThat(created.toString()).doesNotContain(WALLET);

    // a second approval is refused and sends nothing more
    MvcTestResult again = approve(operator("ops-checker02", "ops-checker"), id);
    assertThat(again).hasStatus(409);
    assertThat(body(again).path("code").asString()).isEqualTo("MW-VAL-033");
    verify(core, times(1)).unlock(any(), any(), any(), any(), any());
  }

  @Test
  void aRefusalOrNoAnswerOfTheCoreStillLeavesTheUnlockApproved() {
    when(core.unlock(any(), any(), any(), any(), any()))
        .thenReturn(new CoreAccountClient.UnlockAnswer(CoreAccountClient.Outcome.UNKNOWN, null));
    String id = asked();

    MvcTestResult r = approve(CHECKER, id);

    assertThat(r).hasStatus(200);
    assertThat(body(r).path("status").asString()).isEqualTo("APPROVED");
    assertThat(outbox(LOCK_TOPIC)).as("mw-auth's PIN lock is lifted whatever the core says").hasSize(1);
  }

  @Test
  void theMakerCannotApproveOwnUnlockEvenHoldingTheCheckerRole() {
    RequestPostProcessor both = operator("ops-both01", "ops-maker", "ops-checker");
    MvcTestResult created = ask(both, WALLET, "{\"action\":\"UNLOCK\",\"reason\":\"verified\"}");
    assertThat(created).hasStatus(201);
    String id = body(created).path("id").asString();

    for (MvcTestResult r : List.of(approve(both, id), reject(both, id))) {
      assertThat(r).hasStatus(403);
      assertThat(body(r).path("code").asString()).isEqualTo("MW-AUTH-030");
    }
    verifyNoInteractions(core);
    assertThat(jdbc.queryForObject("select status from change_request where id = ?::uuid", String.class, id))
        .isEqualTo("PENDING");
  }

  @Test
  void onlyAnOpsMakerMayAskForAnUnlock() {
    for (RequestPostProcessor notMaker : List.of(CHECKER, operator("viewer01", "viewer"))) {
      assertThat(ask(notMaker, WALLET, "{\"action\":\"UNLOCK\",\"reason\":\"x\"}")).hasStatus(403);
    }
    assertThat(jdbc.queryForObject("select count(*) from change_request", Integer.class)).isZero();
  }

  @Test
  void aRejectedUnlockSendsNothing() {
    String id = asked();
    assertThat(reject(CHECKER, id)).hasStatus(200);
    verifyNoInteractions(core);
    assertThat(outbox(LOCK_TOPIC)).isEmpty();
    assertThat(audits("admin.change_request.rejected")).hasSize(1);
  }

  @Test
  void oneOpenUnlockPerWalletAndAWalletIdMustBeAnMsisdn() {
    asked();
    MvcTestResult second = ask(MAKER, WALLET, "{\"action\":\"UNLOCK\",\"reason\":\"again\"}");
    assertThat(second).hasStatus(409);
    assertThat(body(second).path("code").asString()).isEqualTo("MW-VAL-031");

    MvcTestResult bad = ask(MAKER, "FEE-POOL", "{\"action\":\"UNLOCK\",\"reason\":\"x\"}");
    assertThat(bad).hasStatus(400);
    assertThat(body(bad).path("code").asString()).isEqualTo("MW-VAL-001");

    MvcTestResult wrongAction = ask(MAKER, "01711000124", "{\"action\":\"REVERSE\",\"reason\":\"x\"}");
    assertThat(wrongAction).hasStatus(400);
    assertThat(body(wrongAction).path("code").asString()).isEqualTo("MW-VAL-001");
  }
}
