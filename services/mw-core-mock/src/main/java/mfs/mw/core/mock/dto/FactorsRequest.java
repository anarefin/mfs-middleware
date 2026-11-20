package mfs.mw.core.mock.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import mfs.mw.core.mock.util.MaskingPolicy;
import mfs.mw.core.mock.util.Sensitive;
import mfs.mw.core.mock.util.SensitiveType;

/**
 * {@code POST /core/v1/customers/{walletId}/verify-factors} (contract {@code FactorsRequest}, ticket
 * 95b): mw-auth's self-service PIN reset asks whether the KYC factors match, each factor a TOKENISE_NID
 * hash bound to the wallet, never in clear. Idempotent by {@code txnId}. No PIN.
 */
public record FactorsRequest(
    @NotNull @Pattern(regexp = WalletTransferRequest.TXN_ID) String txnId,
    @NotNull @Pattern(regexp = AccountUnlockRequest.UUID) String resetId,
    @NotNull @Valid Factors factors) {

  public record Factors(
      @NotNull @Size(max = 256) @Pattern(regexp = "^NID_[A-Z2-7]+$") @Sensitive(SensitiveType.SECRET) String nidLast4Token,
      @NotNull @Size(max = 256) @Pattern(regexp = "^NID_[A-Z2-7]+$") @Sensitive(SensitiveType.SECRET) String dateOfBirthToken,
      @NotNull @Size(min = 1, max = 128) String tokenKid) {

    @Override
    public String toString() {
      return MaskingPolicy.describe(this);
    }
  }
}
