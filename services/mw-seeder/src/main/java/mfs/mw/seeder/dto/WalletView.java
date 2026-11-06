package mfs.mw.seeder.dto;

import mfs.mw.seeder.util.Sensitive;
import mfs.mw.seeder.util.SensitiveType;

/** A core-mock wallet as its seed API answers it (minor units). */
public record WalletView(
    @Sensitive(SensitiveType.MSISDN) String walletId,
    String type,
    String status,
    long balanceMinor,
    long heldMinor,
    long availableMinor) {}
