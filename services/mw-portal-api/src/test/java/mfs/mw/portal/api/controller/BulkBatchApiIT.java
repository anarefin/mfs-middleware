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

  MvcTestResult submit(RequestPostProcessor who, String batchId) {
    return mvc.post().uri(BATCHES + "/{id}/submit", batchId).with(who).exchange();
  }

  /** Uploads and submits the file (ticket 129b): the batch awaits approval. */
  String uploaded(RequestPostProcessor who, String csv) {
    MvcTestResult r = upload(who, csv);
    assertThat(r).hasStatus(201);
    String id = body(r).get("batchId").asString();
    assertThat(submit(who, id)).hasStatusOk();
    return id;
  }

  @Test
  void anUploadIsStoredAsAPreviewAndItsMakerSubmitsIt() {
    MvcTestResult r = upload(MAKER, "msisdn,amount,reference\n01711000001,100.50,A1\n+8801811000002,20,A2\n");
    assertThat(r).hasStatus(201);
    JsonNode b = body(r);
    String id = b.get("batchId").asString();
    assertThat(r).headers().hasValue("Location", BATCHES + "/" + id);
    assertThat(b.get("status").asString()).isEqualTo("PREVIEW");
    assertThat(b.get("validCount").asInt()).isEqualTo(2);
    assertThat(b.get("invalidCount").asInt()).isZero();
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

    assertThat(approve(CHECKER, id)).hasStatus(409);
    MvcTestResult submitted = submit(MAKER, id);
    assertThat(submitted).hasStatusOk();
    assertThat(body(submitted).get("status").asString()).isEqualTo("PENDING_APPROVAL");
    MvcTestResult seen = mvc.get().uri(BATCHES + "/{id}", id).with(CHECKER).exchange();
    assertThat(seen).hasStatusOk();
    assertThat(body(seen).get("status").asString()).isEqualTo("PENDING_APPROVAL");
    MvcTestResult again = submit(MAKER, id);
    assertThat(again).hasStatus(409);
    assertThat(body(again).get("code").asString()).isEqualTo("MW-VAL-036");
  }

  @Test
  void onlyTheMakerWhoUploadedSubmitsAndANoValidRowPreviewCannotBeSubmitted() {
    String id = body(upload(MAKER, rows(1))).get("batchId").asString();
    assertThat(submit(CHECKER, id)).hasStatus(403);
    assertThat(submit(user("maker-2", "ORG001", "org-maker"), id)).hasStatus(403);
    assertThat(submit(user("maker-3", "ORG002", "org-maker"), id)).hasStatus(404);

    String bad = body(upload(MAKER, "msisdn,amount,reference\n0171,1.00,A1\n")).get("batchId").asString();
    MvcTestResult r = submit(MAKER, bad);
    assertThat(r).hasStatus(409);
    assertThat(body(r).get("code").asString()).isEqualTo("MW-VAL-037");
  }

  @Test
  void thePreviewPagesThroughEveryRowWithMaskedMsisdnsAndPerRowCodes() {
    MvcTestResult up =
        upload(MAKER, "msisdn,amount,reference\n01711000001,1.00,A1\n0171,1.00,A2\n01711000003,-5,A3\n01711000004,2.50,A4\n");
    String id = body(up).get("batchId").asString();

    MvcTestResult first = mvc.get().uri(BATCHES + "/{id}/preview?limit=2", id).with(CHECKER).exchange();
    assertThat(first).hasStatusOk();
    JsonNode p = body(first);
    assertThat(p.get("status").asString()).isEqualTo("PREVIEW");
    assertThat(p.get("rowCount").asInt()).isEqualTo(4);
    assertThat(p.get("validCount").asInt()).isEqualTo(2);
    assertThat(p.get("invalidCount").asInt()).isEqualTo(2);
    assertThat(p.get("totalAmount").get("valueMinor").asLong()).isEqualTo(350L);
    assertThat(p.get("rows")).hasSize(2);
    assertThat(p.get("rows").get(0).get("msisdn").asString()).isEqualTo("0171****001");
    assertThat(p.get("rows").get(1).get("valid").asBoolean()).isFalse();
    assertThat(p.get("rows").get(1).has("msisdn")).isFalse();

    JsonNode invalid = body(mvc.get().uri(BATCHES + "/{id}/preview?valid=false", id).with(MAKER).exchange());
    List<String> lines = new ArrayList<>();
    invalid.get("rows").forEach(row -> lines.add(row.get("line").asInt() + ":" + row.get("errors").get(0).asString()));
    assertThat(lines).containsExactly("3:MSISDN_INVALID", "4:AMOUNT_INVALID");

    assertThat(mvc.get().uri(BATCHES + "/{id}/preview?limit=201", id).with(MAKER).exchange()).hasStatus(400);
    assertThat(mvc.get().uri(BATCHES + "/{id}/preview?offset=-1", id).with(MAKER).exchange()).hasStatus(400);
    assertThat(mvc.get().uri(BATCHES + "/{id}/preview", id).with(OTHER_ORG_CHECKER).exchange()).hasStatus(404);
  }

  @Test
  void anXlsxUploadIsStoredLikeTheSameCsv() throws java.io.IOException {
    byte[] file;
    try (var wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
        var out = new java.io.ByteArrayOutputStream()) {
      var sheet = wb.createSheet();
      String[][] cells = {{"msisdn", "amount", "reference"}, {"01711000001", "100.50", "A1"}, {"+8801811000002", "20", "A2"}};
      for (int r = 0; r < cells.length; r++) {
        var row = sheet.createRow(r);
        for (int c = 0; c < 3; c++) {
          row.createCell(c).setCellValue(cells[r][c]);
        }
      }
      wb.write(out);
      file = out.toByteArray();
    }
    MvcTestResult r =
        mvc.post()
            .uri(BATCHES)
            .with(MAKER)
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .content(file)
            .exchange();
    assertThat(r).hasStatus(201);
    String id = body(r).get("batchId").asString();
    assertThat(body(r).get("status").asString()).isEqualTo("PREVIEW");
    assertThat(
            jdbc.queryForList(
                "select line_no || ':' || msisdn || ':' || amount_minor || ':' || reference"
                    + " from batch_item where batch_id = ?::uuid order by line_no",
                String.class,
                id))
        .containsExactly("2:01711000001:10050:A1", "3:01811000002:2000:A2");
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
  void malformedRowsAreKeptAsInvalidWithTheirCodesAndNeverEchoed() {
    MvcTestResult r =
        upload(MAKER, "msisdn,amount,reference\n01711000001,1.00,A1\n0171,1.00,A2\n01711000003,-5,A3\n");
    assertThat(r).hasStatus(201);
    JsonNode b = body(r);
    assertThat(b.get("status").asString()).isEqualTo("PREVIEW");
    assertThat(b.get("rowCount").asInt()).isEqualTo(3);
    assertThat(b.get("invalidCount").asInt()).isEqualTo(2);
    assertThat(b.get("totalAmount").get("valueMinor").asLong()).isEqualTo(100L);
    List<String> errors = new ArrayList<>();
    b.get("rowErrors").forEach(e -> errors.add(e.get("line").asInt() + ":" + e.get("errors").get(0).asString()));
    assertThat(errors).containsExactly("3:MSISDN_INVALID", "4:AMOUNT_INVALID");
    assertThat(r).bodyText().doesNotContain("\"0171\"").doesNotContain("\"-5\"");
    assertThat(
            jdbc.queryForList(
                "select line_no || ':' || status || ':' || coalesce(msisdn, '-') from batch_item"
                    + " where batch_id = ?::uuid order by line_no",
                String.class,
                b.get("batchId").asString()))
        .containsExactly("2:PENDING:01711000001", "3:INVALID:-", "4:INVALID:-");
  }

  @Test
  void aBadHeaderStillRefusesTheWholeFile() {
    MvcTestResult r = upload(MAKER, "phone,amount,reference\n01711000001,1.00,A1\n");
    assertThat(r).hasStatus(400);
    assertThat(body(r).get("code").asString()).isEqualTo("MW-VAL-031");
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
            "line,msisdn,amount,reference,status,txnId,errorCode,batchStatus\n"
                + "2,0171****001,1.25,SAL-1,PENDING,,,APPROVED\n"
                + "3,0171****002,1.25,SAL-2,PENDING,,,APPROVED\n");
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
  void anUnknownStatusFilterIs400AndEveryContractStatusIsAccepted() {
    MvcTestResult bad = mvc.get().uri(BATCHES + "?status=NOPE").with(CHECKER).exchange();
    assertThat(bad).hasStatus(400);
    assertThat(body(bad).get("code").asString()).isEqualTo("MW-VAL-001");
    for (String s :
        List.of(
            "PENDING_APPROVAL", "APPROVED", "REJECTED", "EXECUTING", "PAUSED", "COMPLETED",
            "COMPLETED_WITH_EXCEPTIONS", "EXPIRED")) {
      assertThat(mvc.get().uri(BATCHES + "?status=" + s).with(CHECKER).exchange()).as(s).hasStatusOk();
    }
  }

  @Test
  void anUnknownBatchIs404() {
    assertThat(mvc.get().uri(BATCHES + "/{id}", "0190f0a0-0000-7000-8000-000000000000").with(CHECKER).exchange())
        .hasStatus(404);
    assertThat(mvc.get().uri(BATCHES + "/{id}", "not-a-uuid").with(CHECKER).exchange()).hasStatus(404);
  }
}
