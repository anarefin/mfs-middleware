package mfs.mw.core.mock.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * {@code PUT /core/v1/accounts/{walletId}/kyc-level} (contract {@code KycLevelRequest}, ticket 124b): the level
 * mw-compliance's eKYC match decided, with the application that decided it.
 */
public record KycLevelRequest(
    @NotNull @Pattern(regexp = "^(BASIC|FULL)$") String kycLevel,
    @NotNull @Pattern(regexp = AccountUnlockRequest.UUID) String applicationId) {}
