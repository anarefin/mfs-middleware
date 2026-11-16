package mfs.mw.seeder.client;

import mfs.mw.seeder.dto.WalletView;

/** mw-core-mock's test-only seed API (implemented by {@link CoreMockClient}). */
public interface CoreAccounts {

  /** The wallet as it is now; {@code created} when this call made it, {@code named} when it only got its holder name. */
  record Seeded(boolean created, boolean named, WalletView wallet) {}

  /**
   * Creates the wallet with its opening balance and holder name (may be null) unless it exists; an
   * existing one is not changed, except that a holder name it lacks is filled in.
   */
  Seeded ensureWallet(String walletId, String type, long openingBalanceMinor, String holderName);
}
