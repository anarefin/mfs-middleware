package mfs.mw.portal.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import mfs.mw.portal.api.TestInfrastructure;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The org bulk API end to end inside the service (spec 9.4, ticket 33), against PostgreSQL:
 * upload -> validate -> store as PENDING_APPROVAL -> approve or reject by a different checker of the
 * same org. Tokens are built by spring-security-test with the realm {@code portals} claims.
 */
@SpringBootTest(properties = "mw.outbox.relay.enabled=false")
@AutoConfigureMockMvc
@Import(TestInfrastructure.class)
class BulkBatchApiIT {

  static final String BATCHES = "/portal/org/v1/bulk/batches";
  static final MediaType CSV = MediaType.parseMediaType("text/csv");

  private final MockMvcTester mvc;
  private final JdbcTemplate jdbc;
  private final JsonMapper json;

  @Autowired
  BulkBatchApiIT(MockMvcTester mvc, JdbcTemplate jdbc, JsonMapper json) {
    this.mvc = mvc;
    this.jdbc = jdbc;
    this.json = json;
  }

  @BeforeEach
  void clean() {
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
                    .claim("realm_access", java.util.Map.of("roles", List.of(roles))));
  }

  static final RequestPostProcessor MAKER = user("maker-1", "ORG001", "org-maker");
  static final RequestPostProcessor CHECKER = user("checker-1", "ORG001", "org-checker");
  static final RequestPostProcessor MAKER_CHECKER = user("both-1", "ORG001", "org-maker", "org-checker");
  static final RequestPostProcessor OTHER_ORG_CHECKER = user("checker-2", "ORG002", "org-checker");

  static String rows(int n) {
    StringBuilder b = new StringBuilder("msisdn,amount,reference\n");
    for (int i = 1; i <= n; i++) {
      b.append("0171").append(String.format("%07d", i)).append(",1.25,SAL-").append(i).append('\n');
    }
    return b.toString();
  }

  MvcTestResult upload(RequestPostProcessor who, String csv) {
    return mvc.post().uri(BATCHES).with(who).contentType(CSV).content(csv.getBytes(StandardCharsets.UTF_8)).exchange();
  }

  MvcTestResult approve(RequestPostProcessor who, String batchId) {
    return mvc.post().uri(BATCHES + "/{id}/approve", batchId).with(who).exchange();
  }

  MvcTestResult reject(RequestPostProcessor who, String batchId, String reason) {
    return mvc.post()
        .uri(BATCHES + "/{id}/reject", batchId)
        .with(who)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"reason\":\"" + reason + "\"}")
        .exchange();
  }

  JsonNode body(MvcTestResult r) {
    return json.readTree(r.getResponse().getContentAsByteArray());
  }

  String uploaded(RequestPostProcessor who, String csv) {
    MvcTestResult r = upload(who, csv);
    assertThat(r).hasStatus(201);
    return body(r).get("batchId").asString();
  }

  @Test
  void anUploadIsStoredAwaitingApproval() {
    MvcTestResult r = upload(MAKER, "msisdn,amount,reference\n01711000001,100.50,A1\n+8801811000002,20,A2\n");
    assertThat(r).hasStatus(201);
    JsonNode b = body(r);
    String id = b.get("batchId").asString();
    assertThat(r).headers().hasValue("Location", BATCHES + "/" + id);
    assertThat(b.get("status").asString()).isEqualTo("PENDING_APPROVAL");
    assertThat(b.get("orgId").asString()).isEqualTo("ORG001");
    assertThat(b.get("rowCount").asInt()).isEqualTo(2);
    assertThat(b.get("totalAmount").get("valueMinor").asLong()).isEqualTo(12_050L);
    assertThat(b.get("totalAmount").get("currency").asString()).isEqualTo("BDT");
    assertThat(b.get("makerId").asString()).isEqualTo("maker-1-name");
    assertThat(b.has("checkerId")).isFalse();

    assertThat(
            jdbc.queryForList(
                "select line_no || ':' || msisdn || ':' || amount_minor || ':' || reference || ':' || status"
                    + " from batch_item where batch_id = ?::uuid order by line_no",
                String.class,
                id))
        .containsExactly("2:01711000001:10050:A1:PENDING", "3:01811000002:2000:A2:PENDING");

    MvcTestResult seen = mvc.get().uri(BATCHES + "/{id}", id).with(CHECKER).exchange();
    assertThat(seen).hasStatusOk();
    assertThat(body(seen).get("status").asString()).isEqualTo("PENDING_APPROVAL");
  }

  @Test
  void tenThousandRowsAreAcceptedAndTenThousandAndOneRejected() {
    String id = uploaded(MAKER, rows(10_000));
    assertThat(jdbc.queryForObject("select count(*) from batch_item where batch_id = ?::uuid", Long.class, id))
        .isEqualTo(10_000L);

    MvcTestResult r = upload(MAKER, rows(10_001));
    assertThat(r).hasStatus(400);
    assertThat(body(r).get("code").asString()).isEqualTo("MW-VAL-030");
    assertThat(body(r).get("retryable").asBoolean()).isFalse();
    assertThat(jdbc.queryForObject("select count(*) from batch", Long.class)).isEqualTo(1L);
  }

  @Test
  void malformedRowsAreReportedPerLineAndNothingIsStored() {
    MvcTestResult r =
        upload(MAKER, "msisdn,amount,reference\n01711000001,1.00,A1\n0171,1.00,A2\n01711000003,-5,A3\n");
    assertThat(r).hasStatus(400);
    JsonNode b = body(r);
    assertThat(b.get("code").asString()).isEqualTo("MW-VAL-031");
    assertThat(b.get("correlationId").asString()).isNotBlank();
    List<String> fields = new ArrayList<>();
    b.get("details").forEach(d -> fields.add(d.get("field").asString()));
    assertThat(fields).containsExactly("line 3: msisdn", "line 4: amount");
    assertThat(r).bodyText().doesNotContain("0171,");
    assertThat(jdbc.queryForObject("select count(*) from batch", Long.class)).isZero();
  }

  @Test
  void aDifferentCheckerApproves() {
    String id = uploaded(MAKER, rows(3));
    MvcTestResult r = approve(CHECKER, id);
    assertThat(r).hasStatusOk();
    JsonNode b = body(r);
    assertThat(b.get("status").asString()).isEqualTo("APPROVED");
    assertThat(b.get("checkerId").asString()).isEqualTo("checker-1-name");
    assertThat(b.get("decidedAt").asString()).isNotBlank();
  }

  // --- execution controls and result file (ticket 34) ------------------------------------------

  @Test
  void aCheckerPausesAndResumesAnApprovedBatch() {
    String id = uploaded(MAKER, rows(2));
    assertThat(approve(CHECKER, id)).hasStatusOk();
    MvcTestResult makerTries = mvc.post().uri(BATCHES + "/{id}/pause", id).with(MAKER).exchange();
    assertThat(makerTries).hasStatus(403);
    assertThat(body(makerTries).get("code").asString()).isEqualTo("MW-AUTH-002");

    MvcTestResult paused = mvc.post().uri(BATCHES + "/{id}/pause", id).with(CHECKER).exchange();
    assertThat(paused).hasStatusOk();
    assertThat(body(paused).get("status").asString()).isEqualTo("PAUSED");
    assertThat(body(paused).get("parentTxnId").asString()).isNotBlank();
    assertThat(body(paused).get("progress").get("pending").asInt()).isEqualTo(2);

    MvcTestResult again = mvc.post().uri(BATCHES + "/{id}/pause", id).with(CHECKER).exchange();
    assertThat(again).hasStatus(409);
    assertThat(body(again).get("code").asString()).isEqualTo("MW-VAL-033");

    MvcTestResult resumed = mvc.post().uri(BATCHES + "/{id}/resume", id).with(CHECKER).exchange();
    assertThat(resumed).hasStatusOk();
    assertThat(body(resumed).get("status").asString()).isEqualTo("EXECUTING");
  }

  @Test
  void theResultFileIsCsvWithMaskedMsisdns() {
    String id = uploaded(MAKER, rows(2));
    MvcTestResult early = mvc.get().uri(BATCHES + "/{id}/result", id).with(MAKER).exchange();
    assertThat(early).hasStatus(409);
    assertThat(body(early).get("code").asString()).isEqualTo("MW-VAL-035");

    assertThat(approve(CHECKER, id)).hasStatusOk();
    MvcTestResult r = mvc.get().uri(BATCHES + "/{id}/result", id).with(MAKER).exchange();
    assertThat(r).hasStatusOk();
    assertThat(r).hasContentType("text/csv");
    assertThat(r).headers().hasValue("Content-Disposition", "attachment; filename=\"bulk-" + id + "-result.csv\"");
    assertThat(r)
        .bodyText()
        .isEqualTo(
            "line,msisdn,amount,reference,status,txnId,errorCode\n"
                + "2,0171****001,1.25,SAL-1,PENDING,,\n"
                + "3,0171****002,1.25,SAL-2,PENDING,,\n");
    assertThat(mvc.get().uri(BATCHES + "/{id}/result", id).with(OTHER_ORG_CHECKER).exchange()).hasStatus(404);
  }

  @Test
  void aDifferentCheckerRejectsWithAReason() {
    String id = uploaded(MAKER, rows(3));
    MvcTestResult r = reject(CHECKER, id, "wrong month");
    assertThat(r).hasStatusOk();
    assertThat(body(r).get("status").asString()).isEqualTo("REJECTED");
    assertThat(body(r).get("reason").asString()).isEqualTo("wrong month");

    MvcTestResult noReason = reject(CHECKER, uploaded(MAKER, rows(1)), "");
    assertThat(noReason).hasStatus(400);
    assertThat(body(noReason).get("code").asString()).isEqualTo("MW-VAL-001");
  }

  @Test
  void theMakerCannotApproveOrRejectTheirOwnBatchEvenWithTheCheckerRole() {
    String id = uploaded(MAKER_CHECKER, rows(2));
    MvcTestResult a = approve(MAKER_CHECKER, id);
    assertThat(a).hasStatus(403);
    assertThat(body(a).get("code").asString()).isEqualTo("MW-AUTH-030");
    MvcTestResult rj = reject(MAKER_CHECKER, id, "oops");
    assertThat(rj).hasStatus(403);
    assertThat(body(rj).get("code").asString()).isEqualTo("MW-AUTH-030");
    assertThat(jdbc.queryForObject("select status from batch where batch_id = ?::uuid", String.class, id))
        .isEqualTo("PENDING_APPROVAL");
  }

  @Test
  void aMakerWithoutTheCheckerRoleCannotApprove() {
    String id = uploaded(MAKER, rows(1));
    MvcTestResult r = approve(user("maker-2", "ORG001", "org-maker"), id);
    assertThat(r).hasStatus(403);
    assertThat(body(r).get("code").asString()).isEqualTo("MW-AUTH-002");
  }

  @Test
  void anotherOrgCannotSeeOrDecideTheBatch() {
    String id = uploaded(MAKER, rows(1));
    assertThat(mvc.get().uri(BATCHES + "/{id}", id).with(OTHER_ORG_CHECKER).exchange()).hasStatus(404);
    MvcTestResult r = approve(OTHER_ORG_CHECKER, id);
    assertThat(r).hasStatus(404);
    assertThat(body(r).get("code").asString()).isEqualTo("MW-VAL-003");
    assertThat(body(mvc.get().uri(BATCHES).with(OTHER_ORG_CHECKER).exchange()).get("batches")).isEmpty();
  }

  @Test
  void aDecidedBatchCannotBeDecidedAgain() {
    String id = uploaded(MAKER, rows(1));
    assertThat(approve(CHECKER, id)).hasStatusOk();
    MvcTestResult again = approve(user("checker-3", "ORG001", "org-checker"), id);
    assertThat(again).hasStatus(409);
    assertThat(body(again).get("code").asString()).isEqualTo("MW-VAL-032");
    assertThat(reject(CHECKER, id, "late")).hasStatus(409);
  }

  @Test
  void racingCheckersDecideOnce() throws Exception {
    String id = uploaded(MAKER, rows(1));
    List<Callable<Integer>> calls = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      RequestPostProcessor c = user("checker-r" + i, "ORG001", "org-checker");
      calls.add(i % 2 == 0 ? () -> approve(c, id).getResponse().getStatus() : () -> reject(c, id, "r").getResponse().getStatus());
    }
    List<Integer> statuses = new ArrayList<>();
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      for (var f : pool.invokeAll(calls)) {
        statuses.add(f.get());
      }
    }
    assertThat(statuses).filteredOn(s -> s == 200).hasSize(1);
    assertThat(statuses).filteredOn(s -> s == 409).hasSize(7);
  }

  @Test
  void theOrgListsItsBatchesNewestFirstAndByStatus() {
    String first = uploaded(MAKER, rows(1));
    String second = uploaded(MAKER, rows(2));
    assertThat(approve(CHECKER, first)).hasStatusOk();

    JsonNode all = body(mvc.get().uri(BATCHES).with(CHECKER).exchange()).get("batches");
    assertThat(all).hasSize(2);
    assertThat(all.get(0).get("batchId").asString()).isEqualTo(second);

    JsonNode pending =
        body(mvc.get().uri(BATCHES + "?status=PENDING_APPROVAL").with(CHECKER).exchange()).get("batches");
    assertThat(pending).singleElement().satisfies(b -> assertThat(b.get("batchId").asString()).isEqualTo(second));
  }

  @Test
  void anUnknownBatchIs404() {
    assertThat(mvc.get().uri(BATCHES + "/{id}", "0190f0a0-0000-7000-8000-000000000000").with(CHECKER).exchange())
        .hasStatus(404);
    assertThat(mvc.get().uri(BATCHES + "/{id}", "not-a-uuid").with(CHECKER).exchange()).hasStatus(404);
  }
}
