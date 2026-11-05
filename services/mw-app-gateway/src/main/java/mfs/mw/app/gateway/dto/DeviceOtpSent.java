package mfs.mw.app.gateway.dto;

import java.time.Instant;

/** Answer to a binding OTP request: the wallet (masked) it went to, its expiry and the sends left. */
public record DeviceOtpSent(String wallet, Instant otpExpiresAt, int sendsLeft) {}
