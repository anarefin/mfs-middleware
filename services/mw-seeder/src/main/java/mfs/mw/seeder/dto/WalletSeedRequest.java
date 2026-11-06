package mfs.mw.seeder.dto;

/** Body of core-mock's {@code PUT /core-mock/v1/wallets/{walletId}} (test-only seed API). */
public record WalletSeedRequest(String type, long openingBalanceMinor) {}
