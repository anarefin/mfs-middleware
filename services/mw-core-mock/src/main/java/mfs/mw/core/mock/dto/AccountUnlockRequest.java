package mfs.mw.core.mock.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /core/v1/accounts/{walletId}/unlock} (contract {@code UnlockRequest}, ticket 95b): an ops
 * unlock of a core-side lock that mw-admin-api's maker-checker approved. Idempotent by {@code txnId} (the
 * change request's {@code unlockTxnId}). No PIN.
 */
public record AccountUnlockRequest(
    @NotNull @Pattern(regexp = WalletTransferRequest.TXN_ID) String txnId,
    @NotNull @Pattern(regexp = AccountUnlockRequest.UUID) String changeRequestId,
    @Size(min = 1, max = 128) String makerId,
    @Size(min = 1, max = 128) String checkerId) {

  public static final String UUID = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";
}
