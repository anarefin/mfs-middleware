package mfs.mw.simulators.service.files;

import static org.assertj.core.api.Assertions.assertThat;

import com.prowidesoftware.swift.model.field.Field61;
import com.prowidesoftware.swift.model.mt.mt9xx.MT940;
import com.prowidesoftware.swift.model.mt.mt9xx.MT942;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import mfs.mw.simulators.dto.LedgerEntry;
import mfs.mw.simulators.service.files.TrustStatementGenerator.Mismatch;
import mfs.mw.simulators.service.files.TrustStatementGenerator.TrustAccount;
import org.junit.jupiter.api.Test;

/**
 * The trust bank statements (ticket 123, FRS EXT-06, MCT-05): a ledger fixture becomes an MT940 / MT942 that
 * Prowide parses back with the expected balances and lines, byte for byte the same for the same input, and
 * each injected mismatch changes exactly the lines it names.
 */
class TrustStatementGeneratorTest {

  private static final TrustAccount ACCOUNT = new TrustAccount("TRUSTBDDXXXX", "BITSBDDHXXXX", "0011223344556");
  private static final LocalDate DAY = LocalDate.of(2026, 11, 20);

  // opening 1,000.00 taka; +250.00, -100.50, +49.50 -> closing 1,199.00
  private static final List<LedgerEntry> LEDGER = List.of(
      new LedgerEntry("0196a1b2-0000-7000-8000-000000000001", "CREDIT", "TRANSFER", 25_000, 125_000L,
          Instant.parse("2026-11-20T03:00:00Z")),
      new LedgerEntry("0196a1b2-0000-7000-8000-000000000002", "DEBIT", "TRANSFER", 10_050, 114_950L,
          Instant.parse("2026-11-20T05:30:00Z")),
      new LedgerEntry("0196a1b2-0000-7000-8000-000000000003", "CREDIT", "FEE", 4_950, 119_900L,
          Instant.parse("2026-11-20T09:15:00Z")));

  private final TrustStatementGenerator generator = new TrustStatementGenerator();

  @Test
  void theMt940ParsesBackWithItsBalancesAndLines() {
    String fin = generator.mt940(ACCOUNT, DAY, 100_000, LEDGER, Mismatch.NONE);

    MT940 mt = MT940.parse(fin);
    assertThat(mt.getSender()).isEqualTo("TRUSTBDDXXXX");
    assertThat(mt.getField25().getValue()).isEqualTo("0011223344556");
    assertThat(mt.getField20().getValue()).isEqualTo("TB940261120");
    assertThat(mt.getField60F().getDCMark()).isEqualTo("C");
    assertThat(mt.getField60F().getCurrency()).isEqualTo("BDT");
    assertThat(mt.getField60F().getAmountAsBigDecimal()).isEqualByComparingTo("1000.00");
    assertThat(mt.getField62F().getAmountAsBigDecimal()).isEqualByComparingTo("1199.00");
    assertThat(mt.getField62F().getDate()).isEqualTo("261120");
    assertThat(mt.getField61()).hasSize(3);
    Field61 second = mt.getField61().get(1);
    assertThat(second.getDebitCreditMark()).isEqualTo("D");
    assertThat(second.getAmountAsBigDecimal()).isEqualByComparingTo("100.50");
    assertThat(mt.getField86().get(1).getValue()).isEqualTo("0196a1b2-0000-7000-8000-000000000002");
  }

  @Test
  void theSameInputGivesTheSameBytes() {
    assertThat(generator.mt940(ACCOUNT, DAY, 100_000, LEDGER, Mismatch.NONE))
        .isEqualTo(generator.mt940(ACCOUNT, DAY, 100_000, LEDGER, Mismatch.NONE));
    assertThat(generator.mt942(ACCOUNT, DAY, LocalTime.of(14, 0), LEDGER, Mismatch.NONE))
        .isEqualTo(generator.mt942(ACCOUNT, DAY, LocalTime.of(14, 0), LEDGER, Mismatch.NONE));
  }

  @Test
  void theMt942CarriesTheIntradayLinesAndTotals() {
    MT942 mt = MT942.parse(generator.mt942(ACCOUNT, DAY, LocalTime.of(14, 0), LEDGER, Mismatch.NONE));

    assertThat(mt.getField20().getValue()).isEqualTo("TB9422611201400");
    assertThat(mt.getField13D().getDate()).isEqualTo("261120");
    assertThat(mt.getField13D().getTime()).isEqualTo("1400");
    assertThat(mt.getField61()).hasSize(3);
    assertThat(mt.getField90D().getNumberAsLong()).isEqualTo(1L);
    assertThat(mt.getField90D().getAmountAsBigDecimal()).isEqualByComparingTo("100.50");
    assertThat(mt.getField90C().getNumberAsLong()).isEqualTo(2L);
    assertThat(mt.getField90C().getAmountAsBigDecimal()).isEqualByComparingTo("299.50");
  }

  @Test
  void missingInPartnerDropsTheFirstLinesAndTheClosingFollowsTheLinesShown() {
    MT940 mt = MT940.parse(generator.mt940(ACCOUNT, DAY, 100_000, LEDGER, new Mismatch("MISSING_IN_PARTNER", 1)));

    assertThat(mt.getField61()).hasSize(2);
    assertThat(mt.getField86().get(0).getValue()).endsWith("0002");
    assertThat(mt.getField62F().getAmountAsBigDecimal()).isEqualByComparingTo("949.00");
  }

  @Test
  void missingInUpayAddsLinesNoLedgerHas() {
    MT940 mt = MT940.parse(generator.mt940(ACCOUNT, DAY, 100_000, LEDGER, new Mismatch("MISSING_IN_UPAY", 2)));

    assertThat(mt.getField61()).hasSize(5);
    assertThat(mt.getField86().get(3).getValue()).isEqualTo("TB-EXTRA-261120-1");
    assertThat(mt.getField86().get(4).getValue()).isEqualTo("TB-EXTRA-261120-2");
    BigDecimal extra = mt.getField61().get(3).getAmountAsBigDecimal().add(mt.getField61().get(4).getAmountAsBigDecimal());
    assertThat(mt.getField62F().getAmountAsBigDecimal()).isEqualByComparingTo(new BigDecimal("1199.00").add(extra));
  }

  @Test
  void anAmountMismatchAndAFlippedMarkChangeOnlyTheNamedLines() {
    MT940 amount = MT940.parse(generator.mt940(ACCOUNT, DAY, 100_000, LEDGER, new Mismatch("AMOUNT_MISMATCH", 1)));
    assertThat(amount.getField61().get(0).getAmountAsBigDecimal()).isEqualByComparingTo("251.00");
    assertThat(amount.getField61().get(1).getAmountAsBigDecimal()).isEqualByComparingTo("100.50");

    MT940 flip = MT940.parse(generator.mt940(ACCOUNT, DAY, 100_000, LEDGER, new Mismatch("DC_FLIP", 1)));
    assertThat(flip.getField61().get(0).getDebitCreditMark()).isEqualTo("D");
    assertThat(flip.getField61().get(1).getDebitCreditMark()).isEqualTo("D");
    assertThat(flip.getField62F().getAmountAsBigDecimal()).isEqualByComparingTo("699.00");
  }

  @Test
  void aDayWithoutEntriesHasEqualBalancesAndNoLines() {
    MT940 mt = MT940.parse(generator.mt940(ACCOUNT, DAY, 5_000, List.of(), Mismatch.NONE));

    assertThat(mt.getField61()).isEmpty();
    assertThat(mt.getField60F().getAmountAsBigDecimal()).isEqualByComparingTo("50.00");
    assertThat(mt.getField62F().getAmountAsBigDecimal()).isEqualByComparingTo("50.00");
  }
}
