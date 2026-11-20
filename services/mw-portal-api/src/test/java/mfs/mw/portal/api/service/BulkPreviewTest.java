package mfs.mw.portal.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.nio.charset.StandardCharsets;
import java.util.List;
import mfs.mw.portal.api.dto.BulkFile;
import mfs.mw.portal.api.dto.BulkFileFormat;
import mfs.mw.portal.api.dto.BulkRow;
import mfs.mw.portal.api.dto.InvalidRow;
import mfs.mw.portal.api.exception.MwException;
import org.junit.jupiter.api.Test;

/**
 * The preview of a bulk file (ticket 129b, CH-POR-01): a bad row no longer refuses the file. Every
 * row is kept, the valid ones (which alone are executed and counted in the total) and the invalid
 * ones with their error codes, by line; values that failed a rule are never kept or echoed. Only a
 * problem of the whole file (header, no rows, too large, not a workbook) still refuses it.
 */
class BulkPreviewTest {

  final BulkFileParser parser = new BulkFileParser(10);

  static byte[] csv(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void validAndInvalidRowsAreSeparatedWithTheTotalOfTheValidOnes() {
    BulkFile file =
        parser.parse(
            csv(
                """
                msisdn,amount,reference
                01711000001,10.00,OK-1
                0171100000,10.00,BAD-MSISDN
                01711000003,0,ZERO
                01711000004,25.50,OK-2
                01711000005,10.00,has space
                01711000006,10.00
                01711000007,1.00,OK-1
                x,abc,OK-3
                """),
            BulkFileFormat.CSV);

    assertThat(file.rows())
        .containsExactly(
            new BulkRow(2, "01711000001", 1_000L, "OK-1"), new BulkRow(5, "01711000004", 2_550L, "OK-2"));
    assertThat(file.totalMinor()).isEqualTo(3_550L);
    assertThat(file.rowCount()).isEqualTo(8);
    assertThat(file.invalid())
        .containsExactly(
            new InvalidRow(3, 1_000L, "BAD-MSISDN", List.of("MSISDN_INVALID")),
            new InvalidRow(4, null, "ZERO", List.of("AMOUNT_INVALID")),
            new InvalidRow(6, 1_000L, null, List.of("REFERENCE_INVALID")),
            new InvalidRow(7, null, null, List.of("FIELD_COUNT")),
            new InvalidRow(8, 100L, "OK-1", List.of("REFERENCE_DUPLICATE")),
            new InvalidRow(9, null, "OK-3", List.of("MSISDN_INVALID", "AMOUNT_INVALID")));
  }

  @Test
  void aFileWhoseRowsAreAllInvalidIsStillAPreviewWithNothingToPay() {
    BulkFile file = parser.parse(csv("msisdn,amount,reference\n0171,1.00,R1\n"), BulkFileFormat.CSV);
    assertThat(file.rows()).isEmpty();
    assertThat(file.totalMinor()).isZero();
    assertThat(file.invalid()).extracting(InvalidRow::line).containsExactly(2);
  }

  @Test
  void problemsOfTheWholeFileStillRefuseIt() {
    assertThat(refused(csv("phone,amount,reference\n01711000001,1.00,R1\n")).code()).isEqualTo("MW-VAL-031");
    assertThat(refused(csv("msisdn,amount,reference\n")).code()).isEqualTo("MW-VAL-031");
    StringBuilder b = new StringBuilder("msisdn,amount,reference\n");
    for (int i = 0; i < 11; i++) {
      b.append("x,1.00,R").append(i).append('\n');
    }
    assertThat(refused(csv(b.toString())).code()).isEqualTo("MW-VAL-030");
  }

  @Test
  void anXlsxGivesTheSamePreviewAsTheCsv() {
    BulkFile fromCsv =
        parser.parse(csv("msisdn,amount,reference\n01711000001,1.00,R1\n0171,2.00,R2\n"), BulkFileFormat.CSV);
    BulkFile fromXlsx =
        parser.parse(
            BulkFileParserTest.xlsx(
                BulkFileParserTest.row("msisdn", "amount", "reference"),
                BulkFileParserTest.row("01711000001", "1.00", "R1"),
                BulkFileParserTest.row("0171", "2.00", "R2")),
            BulkFileFormat.XLSX);
    assertThat(fromXlsx).isEqualTo(fromCsv);
  }

  private MwException refused(byte[] file) {
    return catchThrowableOfType(MwException.class, () -> parser.parse(file, BulkFileFormat.CSV));
  }
}
