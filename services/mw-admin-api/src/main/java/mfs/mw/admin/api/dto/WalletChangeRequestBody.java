package mfs.mw.admin.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /admin/v1/wallets/{walletId}/change-requests} (ticket 52): the wallet action the
 * maker asks for and why. Checked against the contract first ({@code filter/BodySchemaAdvice}).
 * {@code reason} is free text: never logged, scrubbed ({@code MaskingPolicy.scrub}) in the audit.
 */
public record WalletChangeRequestBody(@NotNull WalletActionType action, @NotBlank @Size(max = 500) String reason) {

  /** The operator actions on one wallet. */
  public enum WalletActionType {
    /** Clear the PIN lockout (status LOCKED after 5 wrong PINs). */
    UNLOCK
  }
}
