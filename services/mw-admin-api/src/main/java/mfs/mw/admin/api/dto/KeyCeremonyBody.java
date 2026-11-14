package mfs.mw.admin.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** {@code POST /admin/v1/key-ceremonies} (ticket 75): start a key ceremony for {@code keyId}. */
public record KeyCeremonyBody(
    @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9-]{0,62}") String keyId,
    @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{0,63}") String purpose,
    @NotBlank @Size(max = 500) String reason) {}
