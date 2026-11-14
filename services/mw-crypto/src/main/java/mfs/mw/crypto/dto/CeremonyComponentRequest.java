package mfs.mw.crypto.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import mfs.mw.crypto.util.MaskingPolicy;
import mfs.mw.crypto.util.Sensitive;
import mfs.mw.crypto.util.SensitiveType;

/**
 * {@code POST /internal/v1/ceremonies/{ceremonyId}/components} (ticket 75): one custodian's
 * component. The first one opens the ceremony for {@code keyId} / {@code purpose}; the second must
 * name the same key and come from another custodian. The component is never logged or stored.
 *
 * @param custodian the operator's username (the share log's "who")
 * @param component 64 hex digits, a P-256 scalar ({@code util/CeremonyComponents})
 * @param checkValue the component's check value, 6 hex digits (SHA-256 of {@code d·G}, first 3 bytes)
 */
public record CeremonyComponentRequest(
    @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9-]{0,62}") String keyId,
    @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{0,63}") String purpose,
    @NotBlank @Pattern(regexp = "[A-Za-z0-9._@-]{1,128}") String custodian,
    @NotBlank @Pattern(regexp = "[0-9A-Fa-f]{64}") @Sensitive(SensitiveType.SECRET) String component,
    @NotBlank @Pattern(regexp = "[0-9A-Fa-f]{6}") String checkValue) {

  @Override
  public String toString() {
    return MaskingPolicy.describe(this);
  }
}
