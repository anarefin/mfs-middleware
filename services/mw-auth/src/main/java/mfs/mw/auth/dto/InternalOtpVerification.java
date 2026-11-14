package mfs.mw.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import mfs.mw.auth.util.Msisdn;
import mfs.mw.auth.util.Sensitive;
import mfs.mw.auth.util.SensitiveType;

/** Body of {@code POST /internal/v1/otp/verifications} (ticket 72): checks and spends a channel's OTP. */
public record InternalOtpVerification(
    @NotBlank @Pattern(regexp = Msisdn.PATTERN) @Sensitive(SensitiveType.MSISDN) String msisdn,
    @NotBlank @Pattern(regexp = "CHECKOUT") String purpose,
    @NotBlank @Pattern(regexp = InternalOtpRequest.BINDING_ID) String bindingId,
    @NotBlank @Pattern(regexp = "[0-9]{6}") @Sensitive(SensitiveType.SECRET) String otp) {}
