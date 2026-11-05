package mfs.mw.checkout.dto;

import java.time.Instant;

/** Answer to an OTP request: the wallet (masked) it went to, its expiry and the sends left. */
public record OtpSent(String wallet, Instant otpExpiresAt, int sendsLeft) {}
