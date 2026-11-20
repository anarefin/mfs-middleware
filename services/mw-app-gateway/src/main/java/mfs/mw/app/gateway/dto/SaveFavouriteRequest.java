package mfs.mw.app.gateway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import mfs.mw.app.gateway.util.Msisdn;
import mfs.mw.app.gateway.util.Sensitive;
import mfs.mw.app.gateway.util.SensitiveType;

/** {@code POST /app/v1/favourites}. The MSISDN is stored in the canonical {@code 01…} form. */
public record SaveFavouriteRequest(
    @NotBlank @Pattern(regexp = Msisdn.PATTERN) @Sensitive(SensitiveType.MSISDN) String msisdn,
    @Size(max = 40) String label) {}
