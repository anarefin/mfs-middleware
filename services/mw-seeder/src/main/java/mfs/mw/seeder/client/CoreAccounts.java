package mfs.mw.seeder.client;

import mfs.mw.seeder.dto.WalletView;

/** mw-core-mock's test-only seed API (implemented by {@link CoreMockClient}). */
public interface CoreAccounts {

  /** The wallet as it is now; {@code created} when this call made it. */
  record Seeded(boolean created, WalletView wallet) {}

  /** Creates the wallet with its opening balance unless it exists; an existing one is not changed. */
  Seeded ensureWallet(String walletId, String type, long openingBalanceMinor);
}
