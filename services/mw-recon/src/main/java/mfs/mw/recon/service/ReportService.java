package mfs.mw.recon.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mfs.mw.recon.dto.ReconClass;
import mfs.mw.recon.dto.ReconReport;
import mfs.mw.recon.dto.ReportFormat;
import mfs.mw.recon.dto.ReportKind;
import mfs.mw.recon.repository.ReconStore;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.openpdf.text.Document;
import org.openpdf.text.Paragraph;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recon reports (ticket 127, FRS REC-05, REC-06): totals by count and amount per class, computed from mw-recon's
 * own tables only, rendered as CSV, XLSX (Apache POI) or PDF (OpenPDF). Dates are Asia/Dhaka business dates.
 */
@Service
public class ReportService {

  private static final List<String> HEADER = List.of("class", "count", "amount");

  private final ReconStore store;

  public ReportService(ReconStore store) {
    this.store = store;
  }

  /** DAILY: the MT940 and partner-file runs; INTRADAY: the MT942 runs. */
  @Transactional(readOnly = true)
  public ReconReport report(String partnerId, LocalDate from, LocalDate to, ReportKind kind) {
    List<String> sources = kind == ReportKind.INTRADAY ? List.of(ReconService.MT942)
        : List.of(ReconService.MT940, ReconService.PARTNER);
    Map<String, long[]> totals = new LinkedHashMap<>();
    for (ReconClass c : ReconClass.values()) {
      totals.put(c.name(), new long[2]);
    }
    for (String source : sources) {
      for (ReconStore.FindingRow row : store.findingsFor(partnerId, from, to, source)) {
        long[] t = totals.computeIfAbsent(row.reconClass(), k -> new long[2]);
        t[0]++;
        t[1] += row.amountMinor();
      }
    }
    List<ReconReport.Row> rows = new ArrayList<>();
    totals.forEach((c, t) -> rows.add(new ReconReport.Row(c, t[0], t[1])));
    return new ReconReport(partnerId, from, to, kind, rows);
  }

  /**
   * The scheduled daily report: one DAILY report per partner with a run on {@code day}, stored in every format in
   * {@code recon.report} for download. Returns how many partners were reported.
   */
  @Transactional
  public int storeDaily(LocalDate day, java.time.Instant at) {
    List<String> partners = store.partnersOn(day);
    for (String partner : partners) {
      ReconReport report = report(partner, day, day, ReportKind.DAILY);
      for (ReportFormat format : ReportFormat.values()) {
        store.saveReport(partner, ReportKind.DAILY.name(), day, day, format.name(), render(report, format), at);
      }
    }
    return partners.size();
  }

  /** A stored daily report, or a freshly computed one when none is stored (on demand, intraday). */
  @Transactional(readOnly = true)
  public byte[] download(String partnerId, LocalDate from, LocalDate to, ReportKind kind, ReportFormat format) {
    byte[] stored = store.storedReport(partnerId, kind.name(), from, to, format.name());
    return stored != null ? stored : render(report(partnerId, from, to, kind), format);
  }

  public byte[] render(ReconReport report, ReportFormat format) {
    return switch (format) {
      case CSV -> csv(report);
      case XLSX -> xlsx(report);
      case PDF -> pdf(report);
    };
  }

  private static byte[] csv(ReconReport report) {
    StringBuilder out = new StringBuilder("partner,from,to,kind,class,count,amount\n");
    String prefix = String.join(",", csvCell(report.partnerId()), report.from().toString(), report.to().toString(),
        report.kind().name()) + ",";
    for (ReconReport.Row row : report.rows()) {
      out.append(prefix).append(csvCell(row.reconClass())).append(',').append(row.count()).append(',')
          .append(taka(row.amountMinor())).append('\n');
    }
    out.append(prefix).append("TOTAL,").append(report.totalCount()).append(',')
        .append(taka(report.totalAmountMinor())).append('\n');
    return out.toString().getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] xlsx(ReconReport report) {
    try (XSSFWorkbook book = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      Sheet sheet = book.createSheet("recon " + report.kind().name().toLowerCase(java.util.Locale.ROOT));
      Row head = sheet.createRow(0);
      for (int i = 0; i < HEADER.size(); i++) {
        head.createCell(i).setCellValue(HEADER.get(i));
      }
      int r = 1;
      for (ReconReport.Row row : report.rows()) {
        xlsxRow(sheet.createRow(r++), row.reconClass(), row.count(), row.amountMinor());
      }
      xlsxRow(sheet.createRow(r), "TOTAL", report.totalCount(), report.totalAmountMinor());
      book.write(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void xlsxRow(Row row, String name, long count, long amountMinor) {
    row.createCell(0).setCellValue(name);
    row.createCell(1).setCellValue(count);
    row.createCell(2).setCellValue(taka(amountMinor).doubleValue());
  }

  private static byte[] pdf(ReconReport report) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Document doc = new Document();
    PdfWriter.getInstance(doc, out);
    doc.open();
    doc.add(new Paragraph("Reconciliation report " + report.kind().name() + ": " + report.partnerId() + ", "
        + report.from() + " to " + report.to() + " (Asia/Dhaka)"));
    PdfPTable table = new PdfPTable(HEADER.size());
    HEADER.forEach(table::addCell);
    for (ReconReport.Row row : report.rows()) {
      table.addCell(row.reconClass());
      table.addCell(Long.toString(row.count()));
      table.addCell(taka(row.amountMinor()).toPlainString());
    }
    table.addCell("TOTAL");
    table.addCell(Long.toString(report.totalCount()));
    table.addCell(taka(report.totalAmountMinor()).toPlainString());
    doc.add(table);
    doc.close();
    return out.toByteArray();
  }

  private static BigDecimal taka(long minor) {
    return BigDecimal.valueOf(minor, 2);
  }

  /** Quotes a cell holding a comma or quote, and defuses a leading formula character (CSV injection). */
  private static String csvCell(String value) {
    String v = value == null ? "" : value;
    if (!v.isEmpty() && "=+-@".indexOf(v.charAt(0)) >= 0) {
      v = "'" + v;
    }
    if (v.contains(",") || v.contains("\"") || v.contains("\n")) {
      v = "\"" + v.replace("\"", "\"\"") + "\"";
    }
    return v;
  }
}
