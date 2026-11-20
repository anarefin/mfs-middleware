package mfs.mw.admin.api.controller;

import static mfs.mw.admin.api.controller.WalletUnlockApiIT.operator;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.networknt.schema.Schema;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import mfs.mw.admin.api.TestInfrastructure;
import mfs.mw.admin.api.client.CoreAccountClient;
import mfs.mw.admin.api.contracts.ContractSchemas;
import mfs.mw.admin.api.contracts.OpenApiSchemas;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The care PIN reset of ticket 70 through the maker-checker engine: an ops-maker asks (resourceType
 * {@code pin_reset}), a different ops-checker approves. The approval is one transaction (security
 * review of ticket 70): the conditional PENDING -> APPROVED update, PIN_RESET on {@code
 * auth.wallet-lock.v1} through the outbox (mw-auth opens the reset from it), the audit. No remote
 * call happens before the decision, so a reject that wins a race leaves no reset behind.
 */
@SpringBootTest(properties = "mw.outbox.relay.enabled=false")
@AutoConfigureMockMvc
@Import(TestInfrastructure.class)
class PinResetApiIT {

  static final String WALLET = "01711000321";
  static final String LOCK_TOPIC = "auth.wallet-lock.v1";

  static final RequestPostProcessor MAKER = operator("ops-maker01", "viewer", "ops-maker");
  static final RequestPostProcessor CHECKER = operator("ops-checker01", "viewer", "ops-checker");

  @MockitoBean CoreAccountClient core;

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

  @Test
  void anApprovedPinResetSendsOnePinResetCommandToMwAuthAndIsAudited() {
    MvcTestResult created = ask(MAKER, "PIN_RESET");
    assertThat(created).hasStatus(201);
    JsonNode cr = body(created);
    Schema view = OpenApiSchemas.response("api/mw-admin-api/admin.v1.openapi.yaml",
        "/admin/v1/wallets/{walletId}/change-requests", "post", "201");
    assertThat(view.validate(cr)).as("%s", cr).isEmpty();
    assertThat(cr.path("resourceType").asString()).isEqualTo("pin_reset");
    assertThat(cr.path("resourceId").asString()).isEqualTo("0171****321");
    assertThat(cr.path("diff").path("after").path("status").asString()).isEqualTo("RESET");
    assertThat(outbox(LOCK_TOPIC)).as("nothing before the approval").isEmpty();
    assertThat(ask(MAKER, "UNLOCK")).as("an unlock of the same wallet is another request").hasStatus(201);
    assertThat(ask(MAKER, "PIN_RESET")).as("one open reset request per wallet").hasStatus(409);

    String id = cr.path("id").asString();
    MvcTestResult approved = approve(CHECKER, id);
    assertThat(approved).hasStatus(200);
    assertThat(body(approved).path("status").asString()).isEqualTo("APPROVED");
    assertThat(body(approved).path("payload").path("authCommandId").asString()).isEqualTo(id + ":pin-reset");

    List<JsonNode> commands = outbox(LOCK_TOPIC);
    assertThat(commands).hasSize(1);
    JsonNode cmd = commands.getFirst();
    assertThat(ContractSchemas.schema("events/mw-auth/auth.wallet-lock.v1.schema.json").validate(cmd)).as("%s", cmd)
        .isEmpty();
    assertThat(cmd.path("action").asString()).isEqualTo("PIN_RESET");
    assertThat(cmd.path("walletId").asString()).isEqualTo(WALLET);
    assertThat(cmd.path("changeRequestId").asString()).isEqualTo(id);
    assertThat(cmd.path("makerId").asString()).isEqualTo("ops-maker01");
    assertThat(cmd.path("actor").path("id").asString()).isEqualTo("ops-checker01");
    assertThat(jdbc.queryForObject("select msg_key from outbox where topic = ?", String.class, LOCK_TOPIC))
        .isEqualTo(WALLET);
    verify(core, never()).unlock(any(), any(), any(), any(), any());

    JsonNode audit = audits("admin.change_request.approved").getFirst();
    assertThat(ContractSchemas.schema("events/mw-audit-dw/mw.audit.event.v1.schema.json").validate(audit)).isEmpty();
    assertThat(audit.path("resource").asString()).isEqualTo("pin_reset/0171****321");
    assertThat(audit.path("after").path("sentAs").asString()).isEqualTo(LOCK_TOPIC);
    assertThat(audit.toString()).doesNotContain(WALLET);
  }

  @Test
  void aRejectThatWinsTheRaceLeavesNoResetCommand() throws Exception {
    RequestPostProcessor checker2 = operator("ops-checker02", "ops-checker");
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      for (int round = 0; round < 10; round++) {
        reset();
        String id = body(ask(MAKER, "PIN_RESET")).path("id").asString();
        Callable<Integer> approving = () -> approve(CHECKER, id).getResponse().getStatus();
        Callable<Integer> rejecting = () -> reject(checker2, id).getResponse().getStatus();
        Future<Integer> a = pool.submit(approving);
        Future<Integer> r = pool.submit(rejecting);
        int approveStatus = a.get();
        int rejectStatus = r.get();
        assertThat(List.of(approveStatus, rejectStatus)).as("exactly one decision wins").containsExactlyInAnyOrder(200, 409);
        String status = jdbc.queryForObject("select status from change_request where id = ?::uuid", String.class, id);
        int commands = outbox(LOCK_TOPIC).size();
        if ("REJECTED".equals(status)) {
          assertThat(commands).as("round %d: rejected, so no reset", round).isZero();
        } else {
          assertThat(status).isEqualTo("APPROVED");
          assertThat(commands).isEqualTo(1);
        }
      }
    } finally {
      pool.shutdownNow();
    }
    // and the plain order: rejected first, then an approval is refused and sends nothing
    reset();
    String id = body(ask(MAKER, "PIN_RESET")).path("id").asString();
    assertThat(reject(CHECKER, id)).hasStatus(200);
    MvcTestResult late = approve(checker2, id);
    assertThat(late).hasStatus(409);
    assertThat(body(late).path("code").asString()).isEqualTo("MW-VAL-033");
    assertThat(outbox(LOCK_TOPIC)).isEmpty();
  }

  @Test
  void theMakerCannotApproveOwnResetAndOnlyOpsRolesDecide() {
    RequestPostProcessor both = operator("ops-both01", "ops-maker", "ops-checker");
    String id = body(ask(both, "PIN_RESET")).path("id").asString();
    MvcTestResult own = approve(both, id);
    assertThat(own).hasStatus(403);
    assertThat(body(own).path("code").asString()).isEqualTo("MW-AUTH-030");
    assertThat(approve(operator("int-checker01", "integration-checker"), id)).hasStatus(403);
    assertThat(outbox(LOCK_TOPIC)).isEmpty();
    MvcTestResult list = mvc.get().uri("/admin/v1/change-requests?resourceType=pin_reset").with(CHECKER).exchange();
    assertThat(list).hasStatus(200);
    assertThat(body(list).path("items").size()).isEqualTo(1);
  }
}
