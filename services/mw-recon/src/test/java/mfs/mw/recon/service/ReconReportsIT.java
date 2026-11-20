package mfs.mw.recon.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import mfs.mw.recon.TestInfrastructure;
import mfs.mw.recon.dto.ExceptionView;
import mfs.mw.recon.dto.ReconReport;
import mfs.mw.recon.dto.ReportFormat;
import mfs.mw.recon.dto.ReportKind;
import mfs.mw.recon.dto.TrustPosition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

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

  @Autowired
  ReconReportsIT(ReconService recon, ReportService reports, TrustPositionService trust, ExceptionQueueService queue) {
    this.recon = recon;
    this.reports = reports;
    this.trust = trust;
    this.queue = queue;
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
  void theDailyReportIsStoredForDownload() {
    recon.ingestStatement("MT940_rpt127b.fin", FIN.replace("RPT127", "RPT127B").replace("261005", "261006")
        .replace("2610051005", "2610061006").getBytes(StandardCharsets.UTF_8), true);
    LocalDate day = DAY.plusDays(1);

    assertThat(reports.storeDaily(day, Instant.parse("2026-10-06T20:00:00Z"))).isEqualTo(1);

    byte[] csv = reports.download("trustbank", day, day, ReportKind.DAILY, ReportFormat.CSV);
    assertThat(new String(csv, StandardCharsets.UTF_8)).contains("trustbank,2026-10-06,2026-10-06,DAILY,TOTAL,1,100.50");
    assertThat(reports.download("trustbank", day, day, ReportKind.DAILY, ReportFormat.PDF)).isNotEmpty();
  }
}
