package mfs.mw.auth.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code mw.auth.channel-otp.*}: a channel's OTP bound to its own binding (ticket 72: the checkout
 * OTP, bound to the checkout session), kept apart from the device OTP (own slot, own quota; security
 * review of ticket 72).
 *
 * @param ttl validity of an OTP (2 min)
 * @param maxAttempts checks per OTP; the wrong guess that uses the last one voids it
 * @param maxSends OTPs per wallet and purpose within {@code sendWindow}, over all its sessions
 * @param sendWindow the window the sends are counted in
 */
@Validated
@ConfigurationProperties("mw.auth.channel-otp")
public record ChannelOtpProperties(
    @NotNull Duration ttl, @Positive int maxAttempts, @Positive int maxSends, @NotNull Duration sendWindow) {}
