package mfs.mw.auth.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import mfs.mw.auth.util.Msisdn;
import mfs.mw.auth.util.Sensitive;
import mfs.mw.auth.util.SensitiveType;

/**
 * Body of {@code POST /auth/v1/sessions} (login): the MSISDN and either the PIN ({@code
 * encryptedPin}) or, for a wallet without a PIN, a {@code LOGIN} OTP.
 */
public record LoginRequest(
    @NotBlank @Pattern(regexp = Msisdn.PATTERN) @Sensitive(SensitiveType.MSISDN) String msisdn,
    @Valid EncryptedPin encryptedPin,
    @Pattern(regexp = "[0-9]{6}") @Sensitive(SensitiveType.SECRET) String otp) {}
