package mfs.mw.core.mock.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import mfs.mw.core.mock.entity.TariffRow;

/**
 * Tariff arithmetic (ticket 94, FRS 7.1, WAL-04), pure. The row: same txnType, the amount inside its
 * slab (bounds included), channel and customer type equal or {@code *}; of several, the most
 * specific (an exact channel counts more than an exact customer type), then the first. No row: no
 * charges.
 *
 * <p>Rounding, in whole poisha: VAT is inside the fee (Bangladesh MFS tariffs are VAT-inclusive),
 * {@code vat = fee * pct / (100 + pct)} rounded half up; each commission share is {@code (fee - vat) *
 * share / 100} rounded down; FEE_INCOME keeps the rest, so VAT + commission + fee income = fee and the
 * ledger stays balanced to the poisha.
 */
public final class Tariff {

  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  private Tariff() {}

  public static Charges charges(List<TariffRow> sheet, String txnType, String channel, String customerType, long amount) {
    return sheet.stream()
        .filter(r -> r.txnType().equals(txnType))
        .filter(r -> amount >= r.slabMin() && amount <= r.slabMax())
        .filter(r -> matches(r.channel(), channel) && matches(r.customerType(), customerType))
        .max(Comparator.comparingInt(Tariff::specificity))
        .map(Tariff::split)
        .orElse(Charges.NONE);
  }

  /** The charges of one row. */
  static Charges split(TariffRow r) {
    long fee = r.feeMinor();
    BigDecimal pct = r.vatPct() == null ? BigDecimal.ZERO : r.vatPct();
    long vat = BigDecimal.valueOf(fee).multiply(pct).divide(HUNDRED.add(pct), 0, RoundingMode.HALF_UP).longValueExact();
    long net = fee - vat;
    Map<String, Long> shares = new TreeMap<>();
    long commission = 0;
    for (Map.Entry<String, BigDecimal> s : new TreeMap<>(r.commissionSplit()).entrySet()) {
      long share = BigDecimal.valueOf(net).multiply(s.getValue()).divide(HUNDRED, 0, RoundingMode.DOWN).longValueExact();
      shares.put(s.getKey(), share);
      commission += share;
    }
    return new Charges(fee, vat, commission, shares, net - commission);
  }

  private static boolean matches(String rowValue, String value) {
    return TariffRow.ANY.equals(rowValue) || rowValue.equals(value);
  }

  // Stream.max keeps the first of equally specific rows (BinaryOperator.maxBy)
  private static int specificity(TariffRow r) {
    return (TariffRow.ANY.equals(r.channel()) ? 0 : 2) + (TariffRow.ANY.equals(r.customerType()) ? 0 : 1);
  }
}
