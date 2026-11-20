package mfs.mw.ussd.client;

/**
 * The dev tariff sheet ({@code mw-core-mock} {@code seed/v1/tariff.csv}) for the test double: the
 * fee in poisha a CUSTOMER pays on any channel. A type or a slab with no row is free, as the core
 * answers it. The HTTP client does not use this; it asks the core.
 */
public final class SeedTariff {

  private SeedTariff() {}

  public static long feeMinor(String txnType, long amountMinor) {
    return switch (txnType == null ? "" : txnType) {
      case "P2P" -> slab(amountMinor, 500, 1000);
      case "CASH_OUT" -> slab(amountMinor, 1000, 4625);
      case "TOPUP" -> slab(amountMinor, 200, 500);
      case "BILL_PAY" -> slab(amountMinor, 300, 1000);
      default -> 0L;
    };
  }

  /** First slab 100..500000 poisha, second 500001..2500000; outside either, no row. */
  private static long slab(long amountMinor, long low, long high) {
    if (amountMinor >= 100 && amountMinor <= 500_000) {
      return low;
    }
    if (amountMinor >= 500_001 && amountMinor <= 2_500_000) {
      return high;
    }
    return 0L;
  }
}
