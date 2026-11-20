package mfs.mw.recon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.networknt.schema.Schema;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import mfs.mw.recon.TestInfrastructure;
import mfs.mw.recon.contracts.ContractSchemas;
import mfs.mw.recon.exception.ErrorCodes;
import mfs.mw.recon.exception.MwException;
import mfs.mw.recon.dto.ExceptionView;
import mfs.mw.recon.dto.ReconReport;
import mfs.mw.recon.dto.ReportFormat;
import mfs.mw.recon.dto.ReportKind;
import mfs.mw.recon.dto.TrustPosition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reports, trust position and the work queue against the real recon schema (ticket 127, REC-05..07, UC-22): an
 * MT940 is ingested, its closing balance is the trust side, the report totals its findings and an explanation moves
 * an exception out of OPEN.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestInfrastructure.class)
class ReconReportsIT {

  private static final LocalDate DAY = LocalDate.of(2026, 10, 5);

  private static final String FIN = """
      {1:F01BITSBDDHXXXX0000000000}{2:I940TRUSTBDDXXXXN}{4:
      :20:RPT127
      :25:0011223344556
      :28C:278/1
      :60F:C261005BDT1000,00
      :61:2610051005D100,50NTRF0000800000127001//TB1
      FEE
      :86:0196a1b2-0000-7000-8000-000000127001
      :62F:C261005BDT899,50
      -}""";

  private final ReconService recon;
  private final ReportService reports;
  private final TrustPositionService trust;
  private final ExceptionQueueService queue;
  private final JdbcTemplate jdbc;
  private final JsonMapper json;

  @Autowired
  ReconReportsIT(ReconService recon, ReportService reports, TrustPositionService trust, ExceptionQueueService queue,
      JdbcTemplate jdbc, JsonMapper json) {
    this.recon = recon;
    this.reports = reports;
    this.trust = trust;
    this.queue = queue;
    this.jdbc = jdbc;
    this.json = json;
  }

  @Test
  void aStatementDayIsReportedPositionedAndExplained() {
    recon.ingestStatement("MT940_rpt127.fin", FIN.getBytes(StandardCharsets.UTF_8), true);

    ReconReport report = reports.report("trustbank", DAY, DAY, ReportKind.DAILY);
    assertThat(report.rows()).filteredOn(r -> r.reconClass().equals("MISSING_IN_UPAY"))
        .singleElement().satisfies(r -> {
          assertThat(r.count()).isEqualTo(1L);
          assertThat(r.amountMinor()).isEqualTo(10_050L);
        });

    TrustPosition even = trust.compute(DAY, 89_950L);
    assertThat(even.differenceMinor()).isZero();
    assertThat(even.exceptionId()).isNull();

    TrustPosition gap = trust.compute(DAY, 90_000L);
    assertThat(gap.differenceMinor()).isEqualTo(-50L);
    UUID id = gap.exceptionId();
    ExceptionView raised = queue.get(id);
    assertThat(raised.reconClass()).isEqualTo("AMOUNT_MISMATCH");
    assertThat(raised.detail()).isEqualTo("TRUST_POSITION");
    assertThat(raised.state()).isEqualTo("OPEN");
    assertThat(raised.suggestedAction()).isEqualTo("ACCEPT_AS_EXPLAINED");

    List<ExceptionView> open = queue.list("AMOUNT_MISMATCH", "trustbank", "OPEN", null, 100);
    assertThat(open).extracting(ExceptionView::id).contains(id.toString());
    assertThat(queue.list(null, "trustbank", null, 24 * 365 * 10, 100)).isEmpty();

    ExceptionView explained = queue.explain(id, "bank charge posted next day", "seed-recon-maker");
    assertThat(explained.state()).isEqualTo("EXPLAINED");
    assertThat(explained.explainedBy()).isEqualTo("seed-recon-maker");
    assertThat(queue.list("AMOUNT_MISMATCH", "trustbank", "OPEN", null, 100)).extracting(ExceptionView::id)
        .doesNotContain(id.toString());
  }

  @Test
  void anExplanationIsAuditedAndAClosedExceptionIsNotRewritten() throws Exception {
    recon.ingestStatement("MT940_rpt127c.fin", FIN.replace("RPT127", "RPT127C").replace("261005", "261007")
        .replace("2610051005", "2610071007").getBytes(StandardCharsets.UTF_8), true);
    LocalDate day = DAY.plusDays(2);
    UUID open = trust.compute(day, 1L).exceptionId();
    assertThat(trust.compute(day, 2L).exceptionId()).as("a re-run reuses the day's open exception").isEqualTo(open);
    UUID resolved = UUID.randomUUID();
    jdbc.update("""
        insert into recon_exception (id, class, detail, owner, partner_id, state, created_at, explanation)
        values (?, 'AMOUNT_MISMATCH', 'TRUST_POSITION', 'finance', 'trustbank', 'RESOLVED', now(), 'settled')
        """, resolved);

    queue.explain(open, "timing difference", "seed-recon-maker");
    queue.explain(open, "timing difference, bank confirmed", "seed-recon-maker");

    List<String> audits = jdbc.queryForList(
        "select payload::text from outbox where topic = ? and payload->>'resource' = ? order by 1",
        String.class, ReconTopics.AUDIT, "recon_exception/" + open);
    assertThat(audits).hasSize(2);
    Schema schema = ContractSchemas.schema("events/mw-audit-dw/mw.audit.event.v1.schema.json");
    for (String audit : audits) {
      assertThat(schema.validate(json.readTree(audit))).isEmpty();
      assertThat(json.readTree(audit).path("action").asString()).isEqualTo("recon.exception.explained");
      assertThat(json.readTree(audit).path("actor").path("id").asString()).isEqualTo("seed-recon-maker");
    }

    assertThatThrownBy(() -> queue.explain(resolved, "rewrite history", "seed-recon-maker"))
        .isInstanceOfSatisfying(MwException.class, e -> {
          assertThat(e.code()).isEqualTo(ErrorCodes.VAL_STATE_CONFLICT);
          assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
        });
    assertThat(queue.get(resolved).explanation()).isEqualTo("settled");
    assertThat(queue.get(resolved).state()).isEqualTo("RESOLVED");
    assertThat(jdbc.queryForObject("select count(*) from outbox where payload->>'resource' = ?", Integer.class,
        "recon_exception/" + resolved)).isZero();
  }

  @Test
  void aReRunWithANewGapReopensAnExplainedTrustExceptionAndTheSameGapKeepsItsExplanation() {
    recon.ingestStatement("MT940_rpt127d.fin", FIN.replace("RPT127", "RPT127D").replace("261005", "261008")
        .replace("2610051005", "2610081008").getBytes(StandardCharsets.UTF_8), true);
    LocalDate day = DAY.plusDays(3);
    UUID id = trust.compute(day, 90_000L).exceptionId();
    queue.explain(id, "bank charge posted next day", "seed-recon-maker");

    // same gap (closing 899.50 against 900.00): the explanation still covers it
    assertThat(trust.compute(day, 90_000L).exceptionId()).isEqualTo(id);
    assertThat(queue.get(id).state()).isEqualTo("EXPLAINED");
    assertThat(queue.get(id).explanation()).isEqualTo("bank charge posted next day");

    // a different gap: the old explanation no longer approves it
    assertThat(trust.compute(day, 95_000L).exceptionId()).isEqualTo(id);
    ExceptionView reopened = queue.get(id);
    assertThat(reopened.state()).isEqualTo("OPEN");
    assertThat(reopened.explanation()).isNull();
    assertThat(reopened.explainedBy()).isNull();
    assertThat(reopened.explainedAt()).isNull();
    assertThat(reopened.upayAmountMinor()).isEqualTo(95_000L);
  }

  @Test
  void theDailyReportIsStoredForDownload() {
    recon.ingestStatement("MT940_rpt127b.fin", FIN.replace("RPT127", "RPT127B").replace("261005", "261006")
        .replace("2610051005", "2610061006").getBytes(StandardCharsets.UTF_8), true);
    LocalDate day = DAY.plusDays(1);

    assertThat(reports.storeDaily(day, Instant.parse("2026-10-06T20:00:00Z"))).isEqualTo(1);

    byte[] csv = reports.download("trustbank", day, day, ReportKind.DAILY, ReportFormat.CSV);
    assertThat(new String(csv, StandardCharsets.UTF_8)).contains("trustbank,2026-10-06,2026-10-06,DAILY,TOTAL,1,100.50");
    assertThat(reports.download("trustbank", day, day, ReportKind.DAILY, ReportFormat.PDF)).isNotEmpty();

    // REC-06: a later run for that day re-stores the report, so the download is never stale
    recon.ingestStatement("MT940_rpt127b2.fin", FIN.replace("RPT127", "RPT127B2").replace("261005", "261006")
        .replace("2610051005", "2610061006").replace("D100,50", "D200,00").replace("127001", "127b02")
        .getBytes(StandardCharsets.UTF_8), true);
    assertThat(new String(reports.download("trustbank", day, day, ReportKind.DAILY, ReportFormat.CSV),
        StandardCharsets.UTF_8)).contains("trustbank,2026-10-06,2026-10-06,DAILY,TOTAL,2,300.50");
  }
}
