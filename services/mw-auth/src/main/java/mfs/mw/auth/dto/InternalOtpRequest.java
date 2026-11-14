package mfs.mw.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import mfs.mw.auth.util.Msisdn;
import mfs.mw.auth.util.Sensitive;
import mfs.mw.auth.util.SensitiveType;

/**
 * Body of {@code POST /internal/v1/otp} (ticket 72): an OTP for a channel's own binding instead of a
 * device. Only purpose {@code CHECKOUT} (mw-checkout's hosted page; {@code bindingId} = the checkout
 * sessionId); {@code amount} (taka, two decimals) and {@code merchant} go into the SMS template
 * {@code CHECKOUT_OTP}.
 */
public record InternalOtpRequest(
    @NotBlank @Pattern(regexp = Msisdn.PATTERN) @Sensitive(SensitiveType.MSISDN) String msisdn,
    @NotBlank @Pattern(regexp = "CHECKOUT") String purpose,
    @NotBlank @Pattern(regexp = InternalOtpRequest.BINDING_ID) String bindingId,
    @Pattern(regexp = "bn|en") String locale,
    @NotBlank @Pattern(regexp = "[0-9]{1,12}\\.[0-9]{2}") String amount,
    @NotBlank @Size(max = 64) String merchant) {

  /** The binding (a checkout sessionId): the device id rule of signed requests. */
  public static final String BINDING_ID = "[A-Za-z0-9._:-]{8,128}";
}
