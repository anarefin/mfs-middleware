package mfs.mw.core.mock.entity;

import java.time.Instant;

/**
 * Reserved funds of one transaction: {@code amount + fee} stay in the balance but cannot be spent.
 * The charges split of the fee ({@code vat}, {@code commission}, the rest fee income) and the wallet
 * the commit credits ({@code creditWalletId}: a settlement wallet) are decided when it is placed
 * (ticket 94); it expires at {@code expiresAt} and is then released by the core ({@link #EXPIRED}).
 */
public record Hold(
    String id,
    String walletId,
    String txnId,
    String txnType,
    long amountMinor,
    long feeMinor,
    long vatMinor,
    long commissionMinor,
    String creditWalletId,
    String status,
    Instant expiresAt) {

  public static final String HELD = "HELD";
  public static final String COMMITTED = "COMMITTED";
  public static final String RELEASED = "RELEASED";
  public static final String EXPIRED = "EXPIRED";

  public long totalMinor() {
    return amountMinor + feeMinor;
  }

  /** The fee net of VAT and commission: what FEE_INCOME gets on commit. */
  public long feeIncomeMinor() {
    return feeMinor - vatMinor - commissionMinor;
  }
}
