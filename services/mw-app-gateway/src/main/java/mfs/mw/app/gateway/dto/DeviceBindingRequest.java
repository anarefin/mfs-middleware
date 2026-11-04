package mfs.mw.app.gateway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /app/v1/devices}: the device's ECDSA P-256 public key, base64 X.509
 * SubjectPublicKeyInfo (a public key, not personal data). The device id is {@code X-Device-Id}.
 */
public record DeviceBindingRequest(@NotBlank @Size(max = 512) String publicKey) {}
