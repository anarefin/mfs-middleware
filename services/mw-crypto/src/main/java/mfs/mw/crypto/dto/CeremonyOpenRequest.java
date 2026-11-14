package mfs.mw.crypto.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** {@code PUT /internal/v1/ceremonies/{ceremonyId}} (ticket 75): open the ceremony at filing time. */
public record CeremonyOpenRequest(
    @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9-]{0,62}") String keyId,
    @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{0,63}") String purpose) {}
