package mfs.mw.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import mfs.mw.auth.util.MaskingPolicy;
import mfs.mw.auth.util.Sensitive;
import mfs.mw.auth.util.SensitiveType;

/** Body of {@code POST /internal/v1/pin-resets} (auth-internal.v1 CarePinResetRequest, ticket 70). */
public record CarePinResetRequest(
    @NotBlank @Pattern(regexp = "^[A-Za-z0-9._:+-]{1,64}$") @Sensitive(SensitiveType.MSISDN) String walletId,
    @NotNull UUID changeRequestId,
    @NotBlank @Size(max = 128) String makerId,
    @NotBlank @Size(max = 128) String checkerId,
    @Size(max = 500) @Sensitive(SensitiveType.SECRET) String reason,
    @Pattern(regexp = "bn|en") String locale) {

  @Override
  public String toString() {
    return MaskingPolicy.describe(this);
  }
}
