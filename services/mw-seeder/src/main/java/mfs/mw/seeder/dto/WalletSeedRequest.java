package mfs.mw.seeder.dto;

import mfs.mw.seeder.util.Sensitive;
import mfs.mw.seeder.util.SensitiveType;

/**
 * Body of core-mock's {@code PUT /core-mock/v1/wallets/{walletId}} (test-only seed API). {@code
 * holderName} (optional, left out when null): set on create, or on an existing wallet that has none.
 */
public record WalletSeedRequest(String type, long openingBalanceMinor, @Sensitive(SensitiveType.NAME) String holderName) {}
