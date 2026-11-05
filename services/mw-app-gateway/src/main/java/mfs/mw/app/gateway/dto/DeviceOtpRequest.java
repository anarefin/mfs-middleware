package mfs.mw.app.gateway.dto;

import jakarta.validation.constraints.Pattern;

/**
 * Optional body of {@code POST /app/v1/devices/otp}: the language of the OTP SMS (and of the "new
 * device bound" SMS after the binding); mw-notify's default (bn) when absent.
 */
public record DeviceOtpRequest(@Pattern(regexp = "bn|en") String locale) {}
