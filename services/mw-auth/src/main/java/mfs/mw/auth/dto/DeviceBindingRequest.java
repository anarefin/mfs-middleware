package mfs.mw.auth.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import mfs.mw.auth.util.Msisdn;
import mfs.mw.auth.util.Sensitive;
import mfs.mw.auth.util.SensitiveType;

/**
 * Body of {@code POST /auth/v1/devices}: the wallet's MSISDN, the device's ECDSA P-256 public key
 * (base64 X.509 SubjectPublicKeyInfo, a public key, not personal data), the {@code BIND} OTP sent for
 * this device and, when the wallet has a PIN, the PIN. The device id is {@code X-Device-Id}.
 */
public record DeviceBindingRequest(
    @NotBlank @Pattern(regexp = Msisdn.PATTERN) @Sensitive(SensitiveType.MSISDN) String msisdn,
    @NotBlank @Size(max = 512) String publicKey,
    @NotBlank @Pattern(regexp = "[0-9]{6}") @Sensitive(SensitiveType.SECRET) String otp,
    @Valid EncryptedPin encryptedPin) {}
