package mfs.mw.app.gateway.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import mfs.mw.app.gateway.util.MaskingPolicy;
import mfs.mw.app.gateway.util.Sensitive;
import mfs.mw.app.gateway.util.SensitiveType;

/**
 * Body of mw-auth {@code POST /internal/v1/atm-tokens} (contract {@code
 * api/mw-auth/auth-internal.v1.openapi.yaml} {@code AtmTokenRequest}, ticket 120b2): the step-up's
 * wallet, channel, device, the token's amount and currency, the idempotency key and the assertion.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AtmTokenIssue(
    @Sensitive(SensitiveType.MSISDN) String walletId,
    String channel,
    String deviceId,
    long amount,
    String currency,
    String idempotencyKey,
    @Sensitive(SensitiveType.SECRET) String assertion) {

  @Override
  public String toString() {
    return MaskingPolicy.describe(this);
  }
}
