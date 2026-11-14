package mfs.mw.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;
import mfs.mw.auth.util.MaskingPolicy;

/**
 * mw-crypto {@code POST /internal/v1/pin/derive} body ({@code crypto-internal.v1} DerivePinRequest):
 * the PIN HMAC to store for a new PIN, or the weak-PIN rule it breaks ({@code policyInput}: the
 * inputs of the rules mw-auth knows; ticket 70 fills it).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CryptoDerivePinRequest(String walletId, EncryptedPin encryptedPin, Map<String, Object> policyInput) {

  @Override
  public String toString() {
    return MaskingPolicy.describe(this);
  }
}
