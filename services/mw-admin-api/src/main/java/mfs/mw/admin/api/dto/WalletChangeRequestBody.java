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
    /**
     * Clear the PIN lockout: mw-auth's PIN lock (every level) and ADMIN lock on {@code
     * auth.wallet-lock.v1} (ticket 70), and the core's own LOCKED status (UnlockWallet, ticket 52).
     */
    UNLOCK,
    /**
     * A care PIN reset (ticket 70): on approval mw-auth opens a reset the customer completes with a
     * reset OTP and a new PIN, no KYC step; resourceType {@code pin_reset}.
     */
    PIN_RESET
  }
}
