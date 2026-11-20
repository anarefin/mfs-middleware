package mfs.mw.recon.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.prowidesoftware.swift.model.field.Field20;
import com.prowidesoftware.swift.model.field.Field25;
import com.prowidesoftware.swift.model.field.Field28C;
import com.prowidesoftware.swift.model.field.Field61;
import com.prowidesoftware.swift.model.field.Field86;
import com.prowidesoftware.swift.model.mt.mt9xx.MT940;
import com.prowidesoftware.swift.model.mt.mt9xx.MT942;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import mfs.mw.recon.dto.StatementLine;
import org.junit.jupiter.api.Test;

/**
 * Ticket 123's statement layout and a hand-written FIN both become statement lines (ticket 126,
 * FRS REC-01). The generator writes each entry as {@code :61:} (value date, C/D, amount, {@code
 * NTRF}, supplementary details) plus {@code :86:} with the txn id; this test builds that layout
 * with the same Prowide fields and also parses a FIN written by hand.
 */
class Mt940ParserTest {

  private static final LocalDate DAY = LocalDate.of(2026, 11, 20);
  private static final String TXN = "0196a1b2-0000-7000-8000-000000000001";

  private final Mt940Parser parser = new Mt940Parser();

  @Test
  void generatorLayoutMt940ParsesToStatementLines() {
    List<StatementLine> lines = parser.parseMt940(generatorMt940());

    assertThat(lines).hasSize(2);
    StatementLine credit = lines.get(0);
    assertThat(credit.lineNo()).isEqualTo(1);
    assertThat(credit.valueDate()).isEqualTo(DAY);
    assertThat(credit.amountMinor()).isEqualTo(25_000L);
    assertThat(credit.dcMark()).isEqualTo("C");
    assertThat(credit.reference()).isEqualTo(TXN);
    assertThat(credit.supplementary()).isEqualTo("TRANSFER");
    StatementLine debit = lines.get(1);
    assertThat(debit.amountMinor()).isEqualTo(10_050L);
    assertThat(debit.dcMark()).isEqualTo("D");
    assertThat(debit.reference()).isEqualTo("0196a1b2-0000-7000-8000-000000000002");
    assertThat(debit.supplementary()).isEqualTo("FEE");
  }

  @Test
  void generatorLayoutMt942ParsesTheSameLines() {
    List<StatementLine> lines = parser.parseMt942(generatorMt942());

    assertThat(lines).singleElement().satisfies(line -> {
      assertThat(line.valueDate()).isEqualTo(DAY);
      assertThat(line.amountMinor()).isEqualTo(25_000L);
      assertThat(line.dcMark()).isEqualTo("C");
      assertThat(line.reference()).isEqualTo(TXN);
    });
  }

  @Test
  void aHandWrittenMt940AndMt942ParseToTheSameFigures() {
    List<StatementLine> mt940 = parser.parseMt940(HAND_MT940);
    assertThat(mt940).singleElement().satisfies(line -> {
      assertThat(line.valueDate()).isEqualTo(DAY);
      assertThat(line.amountMinor()).isEqualTo(10_050L);
      assertThat(line.dcMark()).isEqualTo("D");
      assertThat(line.reference()).isEqualTo("0196a1b2-0000-7000-8000-000000000009");
      assertThat(line.supplementary()).isEqualTo("FEE");
    });

    List<StatementLine> mt942 = parser.parseMt942(HAND_MT942);
    assertThat(mt942).singleElement().satisfies(line -> {
      assertThat(line.valueDate()).isEqualTo(DAY);
      assertThat(line.amountMinor()).isEqualTo(4_950L);
      assertThat(line.dcMark()).isEqualTo("C");
      assertThat(line.reference()).isEqualTo("TB-EXTRA-261120-1");
    });
  }

  /** The :61:/:86: layout {@code TrustStatementGenerator} writes (ticket 123). */
  private static String generatorMt940() {
    MT940 mt = new MT940("TRUSTBDDXXXX", "BITSBDDHXXXX");
    mt.addField(new Field20().setReference("TB940261120"));
    mt.addField(new Field25().setAccount("0011223344556"));
    mt.addField(new Field28C().setStatementNumber(324).setSequenceNumber(1));
    addEntry(mt::addField, TXN, true, 25_000L, "TRANSFER", 1);
    addEntry(mt::addField, "0196a1b2-0000-7000-8000-000000000002", false, 10_050L, "FEE", 2);
    return mt.message();
  }

  private static String generatorMt942() {
    MT942 mt = new MT942("TRUSTBDDXXXX", "BITSBDDHXXXX");
    mt.addField(new Field20().setReference("TB9422611201400"));
    mt.addField(new Field25().setAccount("0011223344556"));
    addEntry(mt::addField, TXN, true, 25_000L, "TRANSFER", 1);
    return mt.message();
  }

  private static void addEntry(java.util.function.Consumer<com.prowidesoftware.swift.model.field.Field> sink,
      String txnId, boolean credit, long minor, String kind, int n) {
    String compact = txnId.replace("-", "");
    String ownerRef = compact.substring(compact.length() - 16);
    sink.accept(new Field61()
        .setValueDate("261120")
        .setEntryDate("1120")
        .setDebitCreditMark(credit ? "C" : "D")
        .setAmount(BigDecimal.valueOf(minor, 2))
        .setTransactionType("N")
        .setIdentificationCode("TRF")
        .setReferenceForTheAccountOwner(ownerRef)
        .setReferenceOfTheAccountServicingInstitution("TB" + n)
        .setSupplementaryDetails(kind));
    sink.accept(new Field86().setNarrative(txnId));
  }

  private static final String HAND_MT940 = """
      {1:F01BITSBDDHXXXX0000000000}{2:I940TRUSTBDDXXXXN}{4:
      :20:HAND940
      :25:0011223344556
      :28C:324/1
      :60F:C261120BDT1000,00
      :61:2611201120D100,50NTRF0000800000000009//TB9
      FEE
      :86:0196a1b2-0000-7000-8000-000000000009
      :62F:C261120BDT899,50
      -}""";

  private static final String HAND_MT942 = """
      {1:F01BITSBDDHXXXX0000000000}{2:I942TRUSTBDDXXXXN}{4:
      :20:HAND942
      :25:0011223344556
      :28C:324/1
      :34F:BDT0,
      :13D:2611201400+0600
      :61:2611201120C49,50NTRFTBEXTRA2611201//TB1
      ADJUSTMENT
      :86:TB-EXTRA-261120-1
      :90D:0BDT0,
      :90C:1BDT49,50
      -}""";
}
