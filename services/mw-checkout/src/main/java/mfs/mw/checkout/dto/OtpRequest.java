package mfs.mw.checkout.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import mfs.mw.checkout.util.Msisdn;
import mfs.mw.checkout.util.Sensitive;
import mfs.mw.checkout.util.SensitiveType;

/** {@code POST /checkout/pay/v1/{token}/otp}: the wallet that will pay; the OTP goes to it by SMS. */
public record OtpRequest(@NotBlank @Pattern(regexp = Msisdn.PATTERN) @Sensitive(SensitiveType.MSISDN) String wallet) {}
