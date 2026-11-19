package mfs.mw.core.mock.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import mfs.mw.core.mock.entity.TariffRow;
import org.junit.jupiter.api.Test;

/** Tariff slab selection and the VAT / commission arithmetic in poisha (ticket 94, FRS 7.1, WAL-04). */
class TariffTest {

  private static TariffRow row(String txnType, String channel, String customerType, long min, long max, long fee,
      String vatPct, Map<String, BigDecimal> split) {
    return new TariffRow(txnType, channel, customerType, min, max, fee, new BigDecimal(vatPct), split);
  }

  private static final List<TariffRow> SHEET = List.of(
      row("P2P", "*", "*", 100, 100_000, 500, "15", Map.of()),
      row("P2P", "*", "*", 100_001, 2_500_000, 1_000, "15", Map.of()),
      row("P2P", "USSD", "*", 100, 100_000, 300, "15", Map.of()),
      row("CASH_OUT", "*", "CUSTOMER", 100, 2_500_000, 1_000, "15",
          Map.of("AGENT", new BigDecimal("40"), "DISTRIBUTOR", new BigDecimal("10"))));

  @Test
  void picksTheSlabThatHoldsTheAmountBoundsIncluded() {
    assertThat(Tariff.charges(SHEET, "P2P", null, "CUSTOMER", 100_000).fee()).isEqualTo(500);
    assertThat(Tariff.charges(SHEET, "P2P", null, "CUSTOMER", 100_001).fee()).isEqualTo(1_000);
    assertThat(Tariff.charges(SHEET, "P2P", null, "CUSTOMER", 100).fee()).isEqualTo(500);
  }

  @Test
  void anExactChannelBeatsTheWildcard() {
    assertThat(Tariff.charges(SHEET, "P2P", "USSD", "CUSTOMER", 50_000).fee()).isEqualTo(300);
    assertThat(Tariff.charges(SHEET, "P2P", "APP", "CUSTOMER", 50_000).fee()).isEqualTo(500);
  }

  @Test
  void noRowMeansNoCharges() {
    assertThat(Tariff.charges(SHEET, "CASH_IN", null, "AGENT", 50_000)).isEqualTo(Charges.NONE);
    // the customer type of the row must match
    assertThat(Tariff.charges(SHEET, "CASH_OUT", null, "AGENT", 50_000)).isEqualTo(Charges.NONE);
    // outside every slab
    assertThat(Tariff.charges(SHEET, "P2P", null, "CUSTOMER", 99)).isEqualTo(Charges.NONE);
  }

  @Test
  void vatIsPartOfTheFeeAndTheSharesComeOutOfTheRest() {
    // fee 10.00 Tk, VAT 15 % inclusive: 1000 * 15 / 115 = 130.43 -> 130; net 870;
    // agent 40 % = 348, distributor 10 % = 87; fee income keeps 870 - 435 = 435
    Charges c = Tariff.charges(SHEET, "CASH_OUT", null, "CUSTOMER", 200_000);
    assertThat(c.fee()).isEqualTo(1_000);
    assertThat(c.vat()).isEqualTo(130);
    assertThat(c.commissionSplit()).containsEntry("AGENT", 348L).containsEntry("DISTRIBUTOR", 87L);
    assertThat(c.commission()).isEqualTo(435);
    assertThat(c.feeIncome()).isEqualTo(435);
  }

  @Test
  void p2pFeeOfFiveTakaCarriesSixtyFivePoishaVat() {
    Charges c = Tariff.charges(SHEET, "P2P", null, "CUSTOMER", 2_000);
    assertThat(c.vat()).isEqualTo(65);
    assertThat(c.commission()).isZero();
    assertThat(c.feeIncome()).isEqualTo(435);
  }

  @Test
  void vatRoundsHalfUpAndSharesRoundDownSoTheLegsAddUpToTheFee() {
    // 25 * 100 / 200 = 12.5 -> 13 (half up); net 12; 33.3 % of 12 = 3.996 -> 3 (down); income 9
    TariffRow odd = row("X", "*", "*", 1, 1_000, 25, "100", Map.of("AGENT", new BigDecimal("33.3")));
    Charges c = Tariff.charges(List.of(odd), "X", null, "CUSTOMER", 10);
    assertThat(c.vat()).isEqualTo(13);
    assertThat(c.commission()).isEqualTo(3);
    assertThat(c.feeIncome()).isEqualTo(9);
    assertThat(c.vat() + c.commission() + c.feeIncome()).isEqualTo(c.fee());
  }
}
