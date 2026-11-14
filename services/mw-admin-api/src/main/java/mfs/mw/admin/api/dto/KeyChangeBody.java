package mfs.mw.admin.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /admin/v1/keys/change-requests} (ticket 75): a key generation, rotation or retirement
 * the maker (security-admin) asks for. {@code purpose} for KEY_GENERATE, {@code version} (a
 * VERIFY_ONLY one) for KEY_RETIRE. {@code reason} is free text: never logged, scrubbed in the audit.
 */
public record KeyChangeBody(
    @NotNull KeyAction action,
    @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9-]{0,62}") String keyId,
    @Pattern(regexp = "[A-Z][A-Z0-9_]{0,63}") String purpose,
    @Min(1) @Max(999_999) Integer version,
    @NotBlank @Size(max = 500) String reason) {

  /** The key change requests (resourceType {@code key}); KEY_CEREMONY has its own endpoint. */
  public enum KeyAction {
    KEY_GENERATE,
    KEY_ROTATE,
    KEY_RETIRE,
    KEY_CEREMONY
  }
}
