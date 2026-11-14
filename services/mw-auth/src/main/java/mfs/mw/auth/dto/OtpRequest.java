package mfs.mw.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import mfs.mw.auth.util.Msisdn;
import mfs.mw.auth.util.Sensitive;
import mfs.mw.auth.util.SensitiveType;

/**
 * Body of {@code POST /auth/v1/otp}: the wallet's MSISDN, the purpose ({@code BIND}, the default, or
 * {@code LOGIN} for a first PIN) and the SMS language (mw-notify's default bn when absent).
 */
public record OtpRequest(
    @NotBlank @Pattern(regexp = Msisdn.PATTERN) @Sensitive(SensitiveType.MSISDN) String msisdn,
    @Pattern(regexp = "BIND|LOGIN") String purpose,
    @Pattern(regexp = "bn|en") String locale) {}
