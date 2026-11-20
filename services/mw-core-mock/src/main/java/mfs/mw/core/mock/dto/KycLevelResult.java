package mfs.mw.core.mock.dto;

/** The wallet's KYC level after a {@code PUT .../kyc-level} (contract {@code KycLevelResult}, ticket 124b). */
public record KycLevelResult(String walletId, String kycLevel, String applicationId) {}
