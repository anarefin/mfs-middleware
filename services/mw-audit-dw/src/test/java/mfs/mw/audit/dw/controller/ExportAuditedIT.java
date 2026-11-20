package mfs.mw.audit.dw.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import mfs.mw.audit.dw.MutableClock;
import mfs.mw.audit.dw.TestInfrastructure;
import mfs.mw.audit.dw.dto.AuditEvent;
import mfs.mw.audit.dw.security.InternalTokens;
import mfs.mw.audit.dw.service.AuditChainVerifier;
import mfs.mw.audit.dw.service.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Ticket 130 (FRS AUD-05): {@code GET /internal/v1/audit/search/export.csv} returns the search as
 * CSV (same filters, row cap) and every export writes exactly one operator-action record ({@code
 * audit.search.exported}: who, the filters, the row count) into the hash chain.
 */
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {"mw.audit-dw.consumers.enabled=false", "mw.audit-dw.query.export-max-rows=3"})
@Import({TestInfrastructure.class, AuditSearchApiIT.TestClock.class})
class ExportAuditedIT {

  private static final String FAKE_READER_PASSWORD = "test-only-reader-pw";
  private static final String TXN = "01933b8e-7c1a-7b2e-9f3d-2a4c5e6f7c01";

  private final AuditService audit;
  private final MutableClock clock;
  private final JdbcTemplate admin;
  private final JsonMapper json = JsonMapper.builder().build();

  @LocalServerPort int port;

  @Autowired
  ExportAuditedIT(AuditService audit, Clock clock, DataSource dataSource) {
    this.audit = audit;
    this.clock = (MutableClock) clock;
    this.admin = new JdbcTemplate(dataSource);
    admin.execute("alter role audit_reader login password '" + FAKE_READER_PASSWORD + "'");
  }

  @BeforeEach
  void seed() {
    clock.set(AuditSearchApiIT.NOV);
    admin.execute("truncate audit.event, audit.event_key, audit.chain_head, audit.payload");
    for (String[] e : new String[][] {
        {"txn.state.changed", "2026-11-03T08:00:01Z"},
        {"partner.call.completed", "2026-11-03T08:00:02Z"},
        {"txn.state.changed", "2026-11-03T08:00:03Z"}}) {
      audit.record(
          json.readValue(
              """
              {"schemaVersion":1,"auditId":"%s","txnId":"%s","correlationId":"corr-1",
               "actor":{"type":"system","id":"mw-orchestrator"},"action":"%s","resource":"=cmd|calc",
               "after":{"msisdn":"01711234567"},"outcome":"SUCCESS","at":"%s","service":"mw-orchestrator"}
              """
                  .formatted(UUID.randomUUID(), TXN, e[0], e[1]),
              AuditEvent.class));
    }
  }

  private HttpResponse<String> export(String query, String operator) throws Exception {
    HttpRequest.Builder req =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/internal/v1/audit/search/export.csv?" + query))
            .header("Authorization", InternalTokens.bearer("mw-admin-api"));
    if (operator != null) {
      req.header("X-Mw-Operator-Id", operator);
    }
    return HttpClient.newHttpClient().send(req.build(), HttpResponse.BodyHandlers.ofString());
  }

  private List<Map<String, Object>> exportRecords() {
    return admin.queryForList(
        "select actor_type, actor_id, resource, after::text after from audit.event where action = 'audit.search.exported'");
  }

  @Test
  void anExportReturnsCsvAndWritesOneOperatorActionRecord() throws Exception {
    HttpResponse<String> res = export("txnId=" + TXN, "ops.auditor1");

    assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
    assertThat(res.headers().firstValue("Content-Type").orElse("")).startsWith("text/csv");
    assertThat(res.headers().firstValue("X-Mw-Export-Truncated")).contains("false");
    String[] lines = res.body().split("\r\n");
    assertThat(lines[0]).startsWith("at,recordedAt,auditId,txnId,correlationId,hop,");
    assertThat(lines).hasSize(4);
    assertThat(lines[1]).contains(TXN, "ORCHESTRATOR", "txn.state.changed");
    assertThat(lines[2]).contains("PARTNER_CALL");
    // a cell a spreadsheet would run as a formula is defused
    assertThat(lines[1]).contains(",'=cmd|calc,").doesNotContain(",=cmd");

    List<Map<String, Object>> records = exportRecords();
    assertThat(records).hasSize(1);
    assertThat(records.get(0).get("actor_type")).isEqualTo("user");
    assertThat(records.get(0).get("actor_id")).isEqualTo("ops.auditor1");
    JsonNode after = json.readTree((String) records.get(0).get("after"));
    assertThat(after.path("rowCount").asInt()).isEqualTo(3);
    assertThat(after.path("filters").path("txnId").asString()).isEqualTo(TXN);
    assertThat(after.path("operation").asString()).isEqualTo("export.csv");
  }

  @Test
  void theRowCapCutsTheExportAndSaysSo() throws Exception {
    audit.record(
        json.readValue(
            """
            {"schemaVersion":1,"auditId":"%s","txnId":"%s","correlationId":"corr-1",
             "actor":{"type":"system","id":"mw-orchestrator"},"action":"txn.state.changed","resource":"txn/x",
             "outcome":"SUCCESS","at":"2026-11-03T08:00:04Z","service":"mw-orchestrator"}
            """
                .formatted(UUID.randomUUID(), TXN),
            AuditEvent.class));

    HttpResponse<String> res = export("txnId=" + TXN, "ops.auditor1");

    assertThat(res.headers().firstValue("X-Mw-Export-Truncated")).contains("true");
    assertThat(res.body().split("\r\n")).hasSize(4);
    JsonNode after = json.readTree((String) exportRecords().get(0).get("after"));
    assertThat(after.path("truncated").asBoolean()).isTrue();
  }

  @Test
  void anExportWithoutTheOperatorIsRefusedAndNotRecorded() throws Exception {
    HttpResponse<String> res = export("txnId=" + TXN, null);

    assertThat(res.statusCode()).isEqualTo(400);
    assertThat(res.body()).contains("MW-VAL-001");
    assertThat(exportRecords()).isEmpty();
  }

  @Test
  void theOperatorActionRecordIsInsideTheHashChain() throws Exception {
    export("txnId=" + TXN, "ops.auditor1");

    String url = ((com.zaxxer.hikari.HikariDataSource) admin.getDataSource()).getJdbcUrl();
    JdbcTemplate reader = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
        url + (url.contains("?") ? "&" : "?") + "currentSchema=audit", "audit_reader", FAKE_READER_PASSWORD));
    assertThat(new AuditChainVerifier(reader).verify("2026-11").intact()).isTrue();
    assertThat(admin.queryForObject("select count(*) from audit.event", Long.class)).isEqualTo(4);
  }
}
