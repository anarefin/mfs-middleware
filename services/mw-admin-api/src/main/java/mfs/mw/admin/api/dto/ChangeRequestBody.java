package mfs.mw.admin.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /admin/v1/transactions/{txnId}/change-requests} (ticket 37): the action the maker
 * asks for and why. The body is checked against the contract first ({@code filter/BodySchemaAdvice}).
 * {@code reason} is free text: never logged, scrubbed ({@code MaskingPolicy.scrub}) in the audit.
 */
public record ChangeRequestBody(@NotNull TxnActionType action, @NotBlank @Size(max = 500) String reason) {

  /** The operator actions on one transaction (spec 15.2). */
  public enum TxnActionType {
    INQUIRE,
    FORCE_COMMIT,
    FORCE_RELEASE,
    REVERSE
  }
}
