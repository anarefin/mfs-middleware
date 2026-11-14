package mfs.mw.crypto.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import mfs.mw.crypto.util.MaskingPolicy;
import mfs.mw.crypto.util.Sensitive;
import mfs.mw.crypto.util.SensitiveType;

/**
 * Answer of {@code POST /internal/v1/pin/derive}: either the HMAC to store ({@code weak: false}) or
 * the weak-PIN rule the PIN breaks ({@code weak: true}, no HMAC). Never the PIN.
 *
 * @param rule {@code REPEATED_DIGIT}, {@code SEQUENCE}, {@code MSISDN_DIGITS}, {@code BIRTH_YEAR} or
 *     {@code LAST_PIN}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DerivePinResponse(
    boolean weak, String rule, @Sensitive(SensitiveType.SECRET) String hmac, String hmacKeyId) {

  public static DerivePinResponse weak(String rule) {
    return new DerivePinResponse(true, rule, null, null);
  }

  public static DerivePinResponse of(PinHmac h) {
    return new DerivePinResponse(false, null, h.hmac(), h.hmacKeyId());
  }

  @Override
  public String toString() {
    return MaskingPolicy.describe(this);
  }
}
