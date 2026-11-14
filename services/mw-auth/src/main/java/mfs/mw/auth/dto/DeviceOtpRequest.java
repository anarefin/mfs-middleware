package mfs.mw.auth.dto;

import jakarta.validation.constraints.Pattern;

/**
 * Optional body of {@code POST /auth/v1/otp}: the language of the OTP SMS (and of the "new
 * device bound" SMS after the binding); mw-notify's default (bn) when absent.
 */
public record DeviceOtpRequest(@Pattern(regexp = "bn|en") String locale) {}
