package mfs.mw.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import mfs.mw.auth.util.MaskingPolicy;
import mfs.mw.auth.util.Sensitive;
import mfs.mw.auth.util.SensitiveType;

/**
 * Body of {@code POST /internal/v1/atm-tokens} (ticket 120b2): the wallet, channel and device of the
 * app's step-up, the token's amount (minor units) and currency, the idempotency key and the assertion
 * mw-auth issued for the transaction {@code ATM_CASH_OUT} wallet -> {@code PARTNER/<atm partner>} with
 * these values.
 */
public record AtmTokenRequest(
    @NotBlank @Size(max = 64) @Sensitive(SensitiveType.MSISDN) String walletId,
    @NotBlank @Pattern(regexp = "[A-Z][A-Z_]{1,31}") String channel,
    @Size(min = 1, max = 128) String deviceId,
    @Positive long amount,
    @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
    @NotBlank @Size(max = 64) String idempotencyKey,
    @NotBlank @Size(max = 4096) @Sensitive(SensitiveType.SECRET) String assertion) {

  @Override
  public String toString() {
    return MaskingPolicy.describe(this);
  }
}
