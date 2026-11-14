package mfs.mw.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import mfs.mw.auth.util.Sensitive;
import mfs.mw.auth.util.SensitiveType;

/** Body of {@code POST /auth/v1/tokens/refresh}. */
public record RefreshRequest(
    @NotBlank @Size(min = 16, max = 128) @Pattern(regexp = "[A-Za-z0-9_-]+") @Sensitive(SensitiveType.SECRET)
        String refreshToken) {}
