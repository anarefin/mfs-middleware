package mfs.mw.core.mock.entity;

import java.math.BigDecimal;
import java.util.Map;

/**
 * One row of the tariff sheet (ticket 94, FRS 7.1; table {@code tariff}, seeded from {@code
 * seed/v1/tariff.csv}). {@code channel} and {@code customerType} may be {@code *} (any). The slab is
 * {@code [slabMin, slabMax]} in poisha, bounds included. {@code fee} is what the payer pays on top of
 * the amount, VAT included ({@code vatPct} of the net fee); {@code commissionSplit} gives each party's
 * share, in percent of the fee net of VAT (e.g. {@code AGENT 40}, {@code DISTRIBUTOR 10}).
 */
public record TariffRow(
    String txnType,
    String channel,
    String customerType,
    long slabMin,
    long slabMax,
    long feeMinor,
    BigDecimal vatPct,
    Map<String, BigDecimal> commissionSplit) {

  public static final String ANY = "*";

  public TariffRow {
    commissionSplit = Map.copyOf(commissionSplit == null ? Map.of() : commissionSplit);
  }
}
