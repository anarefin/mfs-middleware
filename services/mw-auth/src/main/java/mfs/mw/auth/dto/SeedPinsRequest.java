package mfs.mw.auth.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import mfs.mw.auth.util.Sensitive;
import mfs.mw.auth.util.SensitiveType;

/** Dev only: {@code POST /internal/v1/seed/pins}, the seeder's first PINs (ticket 69). */
public record SeedPinsRequest(@NotNull @Size(min = 1, max = 200) List<@Valid Pin> pins) {

  public record Pin(
      @NotBlank @Pattern(regexp = "[A-Za-z0-9._:+-]{1,64}") @Sensitive(SensitiveType.MSISDN) String walletId,
      @NotNull @Valid EncryptedPin encryptedPin) {}
}
