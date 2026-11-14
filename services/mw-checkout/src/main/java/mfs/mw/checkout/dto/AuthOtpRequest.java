package mfs.mw.checkout.dto;

import mfs.mw.checkout.util.Sensitive;
import mfs.mw.checkout.util.SensitiveType;

/**
 * mw-auth {@code POST /internal/v1/otp} (contract {@code api/mw-auth/auth-internal.v1.openapi.yaml}
 * {@code ChannelOtpRequest}, ticket 72): the checkout OTP, purpose {@code CHECKOUT}, bound to the
 * checkout session ({@code bindingId}); amount (taka) and merchant name for the SMS template.
 */
public record AuthOtpRequest(
    @Sensitive(SensitiveType.MSISDN) String msisdn,
    String purpose,
    String bindingId,
    String locale,
    String amount,
    String merchant) {

  public static final String CHECKOUT = "CHECKOUT";
}
