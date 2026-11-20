package mfs.mw.portal.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import mfs.mw.portal.api.dto.BulkFile;
import mfs.mw.portal.api.dto.BulkFileFormat;
import mfs.mw.portal.api.dto.BulkRow;
import mfs.mw.portal.api.dto.ErrorDetail;
import mfs.mw.portal.api.dto.InvalidRow;
import mfs.mw.portal.api.exception.MwException;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

/**
 * Bulk disbursement file in either format (ticket 129, CH-POR-01): CSV and XLSX give the same rows
 * under the same rules; an XLSX with a formula, an external link, too many rows or that is not a
 * workbook at all is refused.
 */
class BulkFileParserTest {

  final BulkFileParser parser = new BulkFileParser(5);

  static byte[] csv(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  /** A workbook whose first sheet holds the given rows; every cell a text cell. */
  static byte[] xlsx(String[]... rows) {
    try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      Sheet sheet = wb.createSheet("payees");
      for (int r = 0; r < rows.length; r++) {
        if (rows[r] == null) {
          continue;
        }
        Row row = sheet.createRow(r);
        for (int c = 0; c < rows[r].length; c++) {
          row.createCell(c).setCellValue(rows[r][c]);
        }
      }
      wb.write(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static String[] row(String... cells) {
    return cells;
  }

  MwException rejected(byte[] file, BulkFileFormat format) {
    return catchThrowableOfType(MwException.class, () -> parser.parse(file, format));
  }

  @Test
  void csvAndXlsxGiveTheSameRows() {
    List<BulkRow> fromCsv =
        parser.parse(
            csv("msisdn,amount,reference\n01711000001,100.50,SAL-1\n\n+8801811000002,25,SAL-2\n"),
            BulkFileFormat.CSV).rows();
    List<BulkRow> fromXlsx =
        parser.parse(
            xlsx(
                row("msisdn", "amount", "reference"),
                row("01711000001", "100.50", "SAL-1"),
                null,
                row("+8801811000002", "25", "SAL-2")),
            BulkFileFormat.XLSX).rows();
    assertThat(fromXlsx)
        .isEqualTo(fromCsv)
        .containsExactly(
            new BulkRow(2, "01711000001", 10_050L, "SAL-1"), new BulkRow(4, "01811000002", 2_500L, "SAL-2"));
  }

  @Test
  void aNumericAmountCellIsReadAsTaka() throws IOException {
    byte[] file;
    try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      Sheet sheet = wb.createSheet();
      Row h = sheet.createRow(0);
      h.createCell(0).setCellValue("MSISDN");
      h.createCell(1).setCellValue("Amount");
      h.createCell(2).setCellValue("Reference");
      Row r = sheet.createRow(1);
      r.createCell(0).setCellValue("01711000001");
      r.createCell(1).setCellValue(1500.5);
      r.createCell(2).setCellValue("R1");
      Row big = sheet.createRow(2);
      big.createCell(0).setCellValue("01711000002");
      big.createCell(1).setCellValue(10_000_000d);
      big.createCell(2).setCellValue("R2");
      wb.write(out);
      file = out.toByteArray();
    }
    assertThat(parser.parse(file, BulkFileFormat.XLSX).rows())
        .containsExactly(
            new BulkRow(2, "01711000001", 150_050L, "R1"), new BulkRow(3, "01711000002", 1_000_000_000L, "R2"));
  }

  @Test
  void badXlsxRowsAreKeptByLineWithTheirCodesAsForCsv() {
    BulkFile file =
        parser.parse(
            xlsx(
                row("msisdn", "amount", "reference"),
                row("0171100000", "10.00", "R1"),
                row("01711000002", "0", "R2"),
                row("01711000003", "1.00", "R1", "extra")),
            BulkFileFormat.XLSX);
    assertThat(file.rows()).isEmpty();
    assertThat(file.invalid())
        .containsExactly(
            new InvalidRow(2, 1_000L, "R1", List.of("MSISDN_INVALID")),
            new InvalidRow(3, null, "R2", List.of("AMOUNT_INVALID")),
            new InvalidRow(4, null, null, List.of("FIELD_COUNT")));
  }

  @Test
  void aFormulaIsRefusedAndNeverEvaluated() throws IOException {
    byte[] file;
    try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      Sheet sheet = wb.createSheet();
      Row h = sheet.createRow(0);
      h.createCell(0).setCellValue("msisdn");
      h.createCell(1).setCellValue("amount");
      h.createCell(2).setCellValue("reference");
      Row r = sheet.createRow(1);
      r.createCell(0).setCellValue("01711000001");
      r.createCell(1).setCellFormula("5*2");
      r.createCell(2).setCellValue("R1");
      wb.write(out);
      file = out.toByteArray();
    }
    MwException e = rejected(file, BulkFileFormat.XLSX);
    assertThat(e.code()).isEqualTo("MW-VAL-031");
    assertThat(e.details()).singleElement().satisfies(d -> {
      assertThat(d.field()).isEqualTo("line 2: amount");
      assertThat(d.issue()).contains("formula");
    });
  }

  @Test
  void moreRowsThanTheCapAreRefusedAsTooLarge() {
    String[][] rows = new String[7][];
    rows[0] = row("msisdn", "amount", "reference");
    for (int i = 1; i < 7; i++) {
      rows[i] = row("0171100000" + i, "1.00", "R" + i);
    }
    assertThat(rejected(xlsx(rows), BulkFileFormat.XLSX).code()).isEqualTo("MW-VAL-030");
    assertThat(rejected(xlsx(java.util.Arrays.copyOf(rows, 6)), BulkFileFormat.XLSX)).isNull();
  }

  @Test
  void aCellFarOutsideTheThreeColumnsMakesTheRowInvalid() {
    String[] wide = new String[30];
    wide[0] = "01711000001";
    wide[1] = "1.00";
    wide[2] = "R1";
    for (int i = 3; i < wide.length; i++) {
      wide[i] = "";
    }
    wide[29] = "x";
    BulkFile file = parser.parse(xlsx(row("msisdn", "amount", "reference"), wide), BulkFileFormat.XLSX);
    assertThat(file.invalid()).containsExactly(new InvalidRow(2, null, null, List.of("FIELD_COUNT")));
  }

  @Test
  void moreCellsThanTheCapAreRefusedAsTooLarge() {
    String[] wide = new String[500];
    java.util.Arrays.fill(wide, "");
    MwException e = rejected(xlsx(row("msisdn", "amount", "reference"), wide), BulkFileFormat.XLSX);
    assertThat(e.code()).isEqualTo("MW-VAL-030");
  }

  /** Rewrites one entry of a workbook, replacing {@code from} by {@code to} in its text. */
  static byte[] rewrite(byte[] xlsx, String entry, String from, String to) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(xlsx));
        ZipOutputStream zip = new ZipOutputStream(out)) {
      for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
        zip.putNextEntry(new ZipEntry(e.getName()));
        byte[] data = in.readAllBytes();
        if (e.getName().equals(entry)) {
          String text = new String(data, StandardCharsets.UTF_8);
          assertThat(text).contains(from);
          data = text.replace(from, to).getBytes(StandardCharsets.UTF_8);
        }
        zip.write(data);
        zip.closeEntry();
      }
    }
    return out.toByteArray();
  }

  @Test
  void aHugeExponentNumberIsNotExpandedAndFailsAsAnAmount() throws IOException {
    byte[] file;
    try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      Sheet sheet = wb.createSheet();
      Row h = sheet.createRow(0);
      h.createCell(0).setCellValue("msisdn");
      h.createCell(1).setCellValue("amount");
      h.createCell(2).setCellValue("reference");
      Row r = sheet.createRow(1);
      r.createCell(0).setCellValue("01711000001");
      r.createCell(1).setCellValue(987654321d);
      r.createCell(2).setCellValue("R1");
      wb.write(out);
      file = out.toByteArray();
    }
    byte[] hostile = rewrite(file, "xl/worksheets/sheet1.xml", "<v>9.87654321E8</v>", "<v>1E999999999</v>");
    BulkFile read =
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
            java.time.Duration.ofSeconds(2), () -> parser.parse(hostile, BulkFileFormat.XLSX));
    assertThat(read.invalid()).containsExactly(new InvalidRow(2, null, "R1", List.of("AMOUNT_INVALID")));
  }

  @Test
  void anOversizedSharedStringsTableIsRefusedAsTooLarge() throws IOException {
    byte[] file;
    try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      Sheet sheet = wb.createSheet();
      Row h = sheet.createRow(0);
      h.createCell(0).setCellValue("msisdn");
      h.createCell(1).setCellValue("amount");
      h.createCell(2).setCellValue("reference");
      Sheet hidden = wb.createSheet("hidden");
      for (int i = 0; i < 200; i++) {
        hidden.createRow(i).createCell(0).setCellValue("filler-" + i);
      }
      wb.write(out);
      file = out.toByteArray();
    }
    assertThat(rejected(file, BulkFileFormat.XLSX).code()).isEqualTo("MW-VAL-030");
  }

  @Test
  void aWorkbookWithAnExternalLinkIsRefused() throws IOException {
    byte[] plain = xlsx(row("msisdn", "amount", "reference"), row("01711000001", "1.00", "R1"));
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(plain));
        ZipOutputStream zip = new ZipOutputStream(out)) {
      for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
        zip.putNextEntry(new ZipEntry(e.getName()));
        in.transferTo(zip);
        zip.closeEntry();
      }
      zip.putNextEntry(new ZipEntry("xl/externalLinks/externalLink1.xml"));
      zip.write("<externalLink/>".getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    }
    MwException e = rejected(out.toByteArray(), BulkFileFormat.XLSX);
    assertThat(e.code()).isEqualTo("MW-VAL-031");
    assertThat(e.details()).singleElement().satisfies(d -> assertThat(d.issue()).contains("external"));
  }

  @Test
  void bytesThatAreNotAWorkbookAreRefused() {
    MwException e = rejected(csv("msisdn,amount,reference\n01711000001,1.00,R1\n"), BulkFileFormat.XLSX);
    assertThat(e.code()).isEqualTo("MW-VAL-031");
    assertThat(e.details()).extracting(ErrorDetail::field).containsExactly("file");
  }

  @Test
  void anEmptySheetHasNoRows() {
    assertThat(rejected(xlsx(row("msisdn", "amount", "reference")), BulkFileFormat.XLSX).code())
        .isEqualTo("MW-VAL-031");
  }

  @Test
  void theFormatFollowsTheContentType() {
    assertThat(BulkFileFormat.of("text/csv")).isEqualTo(BulkFileFormat.CSV);
    assertThat(BulkFileFormat.of("text/csv;charset=UTF-8")).isEqualTo(BulkFileFormat.CSV);
    assertThat(BulkFileFormat.of("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .isEqualTo(BulkFileFormat.XLSX);
  }
}
