package mfs.mw.checkout.dto;

import mfs.mw.checkout.util.Sensitive;
import mfs.mw.checkout.util.SensitiveType;

/**
 * mw-auth {@code POST /internal/v1/otp/verifications} ({@code ChannelOtpVerification}, ticket 72):
 * checks the session's OTP and spends it.
 */
public record AuthOtpVerification(
    @Sensitive(SensitiveType.MSISDN) String msisdn,
    String purpose,
    String bindingId,
    @Sensitive(SensitiveType.SECRET) String otp) {}
