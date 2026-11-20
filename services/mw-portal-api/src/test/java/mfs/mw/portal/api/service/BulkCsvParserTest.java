package mfs.mw.portal.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.tuple;

import java.nio.charset.StandardCharsets;
import java.util.List;
import mfs.mw.portal.api.dto.BulkFile;
import mfs.mw.portal.api.dto.BulkRow;
import mfs.mw.portal.api.dto.InvalidRow;
import mfs.mw.portal.api.dto.ErrorDetail;
import mfs.mw.portal.api.exception.MwException;
import org.junit.jupiter.api.Test;

/** Bulk disbursement CSV (spec 9.4, ticket 33): header, row rules, the row cap, per-line error codes (129b). */
class BulkCsvParserTest {

  final BulkCsvParser parser = new BulkCsvParser(10_000);

  static byte[] csv(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  static byte[] rows(int n) {
    StringBuilder b = new StringBuilder("msisdn,amount,reference\n");
    for (int i = 1; i <= n; i++) {
      b.append("0171").append(String.format("%07d", i)).append(",10.00,R").append(i).append('\n');
    }
    return csv(b.toString());
  }

  MwException rejected(byte[] file) {
    return catchThrowableOfType(MwException.class, () -> parser.parse(file));
  }

  @Test
  void validRowsAreReadWithTheirLineAndAmountInPoisha() {
    List<BulkRow> rows =
        parser.parse(
            csv("msisdn,amount,reference\r\n01711000001,100.50,SAL-NOV-001\r\n+8801811000002,25,SAL-NOV-002\r\n"))
            .rows();
    assertThat(rows)
        .containsExactly(
            new BulkRow(2, "01711000001", 10_050L, "SAL-NOV-001"),
            new BulkRow(3, "01811000002", 2_500L, "SAL-NOV-002"));
  }

  @Test
  void aByteOrderMarkQuotesSurroundingSpacesAndBlankLinesAreTolerated() {
    List<BulkRow> rows =
        parser.parse(csv("﻿MSISDN, Amount ,Reference\n\n\"01711000001\", 1.5 ,\"R1\"\n\n")).rows();
    assertThat(rows).containsExactly(new BulkRow(3, "01711000001", 150L, "R1"));
  }

  @Test
  void tenThousandRowsAreAccepted() {
    assertThat(parser.parse(rows(10_000)).rows()).hasSize(10_000);
  }

  @Test
  void tenThousandAndOneRowsAreRejectedAsTooLarge() {
    MwException e = rejected(rows(10_001));
    assertThat(e.code()).isEqualTo("MW-VAL-030");
    assertThat(e.status().value()).isEqualTo(400);
    assertThat(e.getMessage()).contains("10000");
  }

  @Test
  void everyMalformedRowIsKeptByLineWithItsCodes() {
    BulkFile file =
        parser.parse(
            csv(
                """
                msisdn,amount,reference
                01711000001,10.00,OK-1
                0171100000,10.00,BAD-MSISDN
                01711000003,0,ZERO
                01711000004,1.234,THREE-DECIMALS
                01711000005,abc,NOT-A-NUMBER
                01711000006,10.00,
                01711000007,10.00,has space
                01711000008,10.00
                01711000009,10.00,OK-1
                """));
    assertThat(file.rows()).extracting(BulkRow::line).containsExactly(2);
    assertThat(file.invalid())
        .extracting(InvalidRow::line, InvalidRow::errors)
        .containsExactly(
            tuple(3, List.of("MSISDN_INVALID")),
            tuple(4, List.of("AMOUNT_INVALID")),
            tuple(5, List.of("AMOUNT_INVALID")),
            tuple(6, List.of("AMOUNT_INVALID")),
            tuple(7, List.of("REFERENCE_INVALID")),
            tuple(8, List.of("REFERENCE_INVALID")),
            tuple(9, List.of("FIELD_COUNT")),
            tuple(10, List.of("REFERENCE_DUPLICATE")));
  }

  @Test
  void aBadValueIsNeverKept() {
    BulkFile file = parser.parse(csv("msisdn,amount,reference\n0171100000,10.00,R1\n01711000002,1,has space\n"));
    assertThat(file.invalid().toString()).doesNotContain("0171100000").doesNotContain("has space");
  }

  @Test
  void aWrongHeaderIsReportedOnLineOne() {
    MwException e = rejected(csv("phone,amount,reference\n01711000001,10.00,R1\n"));
    assertThat(e.code()).isEqualTo("MW-VAL-031");
    assertThat(e.details()).extracting(ErrorDetail::field).containsExactly("line 1");
  }

  @Test
  void aFileWithoutRowsIsRejected() {
    assertThat(rejected(csv("msisdn,amount,reference\n")).code()).isEqualTo("MW-VAL-031");
    assertThat(rejected(csv("")).code()).isEqualTo("MW-VAL-031");
  }

  @Test
  void everyBadRowIsKeptHoweverMany() {
    StringBuilder b = new StringBuilder("msisdn,amount,reference\n");
    for (int i = 0; i < 250; i++) {
      b.append("x,1.00,R").append(i).append('\n');
    }
    assertThat(parser.parse(csv(b.toString())).invalid()).hasSize(250);
  }
}
