package mfs.mw.recon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import mfs.mw.recon.dto.ReconReport;
import mfs.mw.recon.dto.ReportFormat;
import mfs.mw.recon.dto.ReportKind;
import mfs.mw.recon.repository.ReconStore;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.openpdf.text.pdf.PdfReader;

class ReportServiceTest {

  private static final LocalDate DAY = LocalDate.of(2026, 11, 19);

  private final ReconStore store = mock(ReconStore.class);
  private final ReportService reports = new ReportService(store);

  /** Fixture: two matched lines (100.00 + 50.00), one amount mismatch (25.00), one missing in Upay (10.00). */
  private ReconReport fixture() {
    when(store.findingsFor("billpay-desco", DAY, DAY, "PARTNER")).thenReturn(List.of(
        new ReconStore.FindingRow("MATCHED", 10_000L),
        new ReconStore.FindingRow("MATCHED", 5_000L),
        new ReconStore.FindingRow("AMOUNT_MISMATCH", 2_500L),
        new ReconStore.FindingRow("MISSING_IN_UPAY", 1_000L)));
    return reports.report("billpay-desco", DAY, DAY, ReportKind.DAILY);
  }

  @Test
  void totalsPerClassMatchTheFixture() {
    ReconReport report = fixture();

    assertThat(report.rows()).extracting(ReconReport.Row::reconClass, ReconReport.Row::count, ReconReport.Row::amountMinor)
        .containsExactly(
            tuple("MATCHED", 2L, 15_000L),
            tuple("MISSING_IN_PARTNER", 0L, 0L),
            tuple("MISSING_IN_UPAY", 1L, 1_000L),
            tuple("AMOUNT_MISMATCH", 1L, 2_500L),
            tuple("STATUS_MISMATCH", 0L, 0L));
    assertThat(report.totalCount()).isEqualTo(4L);
    assertThat(report.totalAmountMinor()).isEqualTo(18_500L);
  }

  @Test
  void intradayReadsTheMt942Runs() {
    when(store.findingsFor("trust", DAY, DAY, "TRUST_MT942")).thenReturn(List.of(new ReconStore.FindingRow("MATCHED", 700L)));

    ReconReport report = reports.report("trust", DAY, DAY, ReportKind.INTRADAY);

    assertThat(report.totalCount()).isEqualTo(1L);
    assertThat(report.totalAmountMinor()).isEqualTo(700L);
  }

  @Test
  void csvCarriesOneLinePerClassAndTheTotal() {
    String csv = new String(reports.render(fixture(), ReportFormat.CSV), StandardCharsets.UTF_8);

    assertThat(csv.lines().toList()).containsExactly(
        "partner,from,to,kind,class,count,amount",
        "billpay-desco,2026-11-19,2026-11-19,DAILY,MATCHED,2,150.00",
        "billpay-desco,2026-11-19,2026-11-19,DAILY,MISSING_IN_PARTNER,0,0.00",
        "billpay-desco,2026-11-19,2026-11-19,DAILY,MISSING_IN_UPAY,1,10.00",
        "billpay-desco,2026-11-19,2026-11-19,DAILY,AMOUNT_MISMATCH,1,25.00",
        "billpay-desco,2026-11-19,2026-11-19,DAILY,STATUS_MISMATCH,0,0.00",
        "billpay-desco,2026-11-19,2026-11-19,DAILY,TOTAL,4,185.00");
  }

  @Test
  void xlsxReadsBack() throws Exception {
    byte[] xlsx = reports.render(fixture(), ReportFormat.XLSX);

    try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
      Sheet sheet = book.getSheetAt(0);
      assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("class");
      assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("MATCHED");
      assertThat(sheet.getRow(1).getCell(1).getNumericCellValue()).isEqualTo(2.0);
      assertThat(sheet.getRow(1).getCell(2).getNumericCellValue()).isEqualTo(150.00);
      assertThat(sheet.getRow(6).getCell(0).getStringCellValue()).isEqualTo("TOTAL");
      assertThat(sheet.getRow(6).getCell(2).getNumericCellValue()).isEqualTo(185.00);
    }
  }

  @Test
  void pdfIsNonEmptyWithOnePage() throws Exception {
    byte[] pdf = reports.render(fixture(), ReportFormat.PDF);

    assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    PdfReader reader = new PdfReader(pdf);
    try {
      assertThat(reader.getNumberOfPages()).isEqualTo(1);
    } finally {
      reader.close();
    }
  }
}
