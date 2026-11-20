package mfs.mw.compliance.dto;

/** The core's {@code KycLevelRequest} (core-wallet.v1, ticket 124b): the level and the application that decided it. */
public record CoreKycLevelRequest(String kycLevel, String applicationId) {}
