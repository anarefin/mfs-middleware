package mfs.mw.auth.entity;

import java.time.Instant;

/**
 * A row of {@code device_otp}: the wallet's current device binding OTP (its hash only, null
 * once spent or void), the device it was asked for, its checks, the sends of the window and its
 * purpose ({@code BIND} or {@code LOGIN}, ticket 69).
 */
public record DeviceOtp(
    String walletId, String deviceId, String otpHash, String locale, Instant expiresAt, int attempts, int sends,
    String purpose) {}
