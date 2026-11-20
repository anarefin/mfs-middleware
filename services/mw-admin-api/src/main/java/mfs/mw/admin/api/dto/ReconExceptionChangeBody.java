package mfs.mw.admin.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /admin/v1/recon-exceptions/{exceptionId}/change-requests} (ticket 127b): the action a recon-maker asks
 * for on a reconciliation exception and why. Checked against the contract first ({@code BodySchemaAdvice}). {@code
 * reason} is free text: never logged, scrubbed in the audit.
 */
public record ReconExceptionChangeBody(@NotNull ReconAction action, @NotBlank @Size(max = 500) String reason) {

  /** The ticket 37 actions a recon exception may lead to. */
  public enum ReconAction {
    FORCE_COMMIT,
    REVERSE
  }
}
