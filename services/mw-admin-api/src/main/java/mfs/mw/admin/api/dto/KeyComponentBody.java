package mfs.mw.admin.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import mfs.mw.admin.api.util.MaskingPolicy;
import mfs.mw.admin.api.util.Sensitive;
import mfs.mw.admin.api.util.SensitiveType;

/**
 * {@code POST /admin/v1/key-ceremonies/{id}/components} (ticket 75): one custodian's component and
 * its check value. The component goes to mw-crypto in the request only: never stored, logged or
 * audited here; {@link #toString()} masks it.
 */
public record KeyComponentBody(
    @NotBlank @Pattern(regexp = "[0-9A-Fa-f]{64}") @Sensitive(SensitiveType.SECRET) String component,
    @NotBlank @Pattern(regexp = "[0-9A-Fa-f]{6}") String checkValue) {

  @Override
  public String toString() {
    return MaskingPolicy.describe(this);
  }
}
