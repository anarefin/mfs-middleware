package mfs.mw.crypto.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * {@code POST /internal/v1/keys} (dev and bootstrap only until the key ceremony, ticket 75).
 *
 * @param purpose a spec 18.8 key purpose ({@code JWT_SIGN}, {@code TOKENISE_MSISDN}, ...)
 * @param keyId optional; default is the purpose in lower case with hyphens ({@code jwt-sign})
 */
public record GenerateKeyRequest(
    @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{0,63}") String purpose,
    @Pattern(regexp = "[a-z0-9][a-z0-9-]{0,62}") String keyId) {}
