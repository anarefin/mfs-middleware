package mfs.mw.auth.entity;

import java.time.Instant;

/**
 * A row of {@code device_otp}: the wallet's current device binding OTP (its hash only, null
 * once spent or void), the device it was asked for, its checks and the sends of the window.
 */
public record DeviceOtp(
    String walletId, String deviceId, String otpHash, String locale, Instant expiresAt, int attempts, int sends) {}
