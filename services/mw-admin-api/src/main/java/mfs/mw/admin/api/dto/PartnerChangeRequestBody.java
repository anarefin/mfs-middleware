package mfs.mw.admin.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /admin/v1/partners/{partnerId}/change-requests} (ticket 38): the change the maker
 * asks for and why. The body is checked against the contract first ({@code filter/BodySchemaAdvice});
 * which fields an action needs is the service's rule. {@code reason} is free text: never logged,
 * scrubbed in the audit.
 */
public record PartnerChangeRequestBody(
    @NotNull PartnerAction action,
    @NotBlank @Size(max = 500) String reason,
    String family,
    Timeouts timeouts,
    PartnerConfigValue config) {

  /** The partner changes of spec 15.2 Partners the console offers (ticket 38). */
  public enum PartnerAction {
    ENABLE,
    DISABLE,
    SET_TIMEOUTS,
    UPSERT
  }

  /** New timeouts; a field left out keeps its value. */
  public record Timeouts(
      @Min(1) @Max(120_000) Long connectTimeoutMs,
      @Min(1) @Max(120_000) Long timeoutMs,
      @Min(1) @Max(125_000) Long readTimeoutMs) {}
}
