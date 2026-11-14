package mfs.mw.crypto.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * {@code PUT /internal/v1/key-changes/{changeRequestId}} (ticket 75): a key change a maker and a
 * different checker approved in mw-admin-api. Applied once per change request id; a repeat answers
 * the key as it is now.
 *
 * @param purpose GENERATE only
 * @param version RETIRE only: the VERIFY_ONLY version to retire
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KeyChangeRequest(
    @NotNull Action action,
    @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9-]{0,62}") String keyId,
    @Pattern(regexp = "[A-Z][A-Z0-9_]{0,63}") String purpose,
    @Min(1) @Max(999_999) Integer version,
    @NotBlank @Pattern(regexp = "[A-Za-z0-9._@-]{1,128}") String makerId,
    @NotBlank @Pattern(regexp = "[A-Za-z0-9._@-]{1,128}") String checkerId) {

  public enum Action {
    GENERATE,
    ROTATE,
    RETIRE
  }
}
