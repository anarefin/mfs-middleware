package mfs.mw.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import mfs.mw.auth.util.Sensitive;
import mfs.mw.auth.util.SensitiveType;

/**
 * Body of {@code POST /auth/v1/devices}: the device's ECDSA P-256 public key, base64 X.509
 * SubjectPublicKeyInfo (a public key, not personal data), and the OTP sent by SMS to the token's
 * wallet for this device ({@code POST /auth/v1/otp}). The device id is {@code X-Device-Id}.
 */
public record DeviceBindingRequest(
    @NotBlank @Size(max = 512) String publicKey,
    @NotBlank @Pattern(regexp = "[0-9]{6}") @Sensitive(SensitiveType.SECRET) String otp) {}
