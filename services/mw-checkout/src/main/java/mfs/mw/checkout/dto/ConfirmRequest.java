package mfs.mw.checkout.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import mfs.mw.checkout.util.Sensitive;
import mfs.mw.checkout.util.SensitiveType;

/**
 * {@code POST /checkout/pay/v1/{token}/confirm}: the OTP and the PIN, encrypted by the page with
 * the PIN transport key ({@code pinKey}; never plain; this service only forwards the ciphertext to
 * mw-auth, ticket 72).
 */
public record ConfirmRequest(
    @NotBlank @Pattern(regexp = "[0-9]{6}") @Sensitive(SensitiveType.SECRET) String otp,
    @Valid @NotNull EncryptedPin encryptedPin) {}
