package mfs.mw.admin.api.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * {@code mw.change-requests.*} (maker-checker, spec 15.3, ticket 37).
 *
 * @param ttl how long a request may wait for a checker (spec: 72 h)
 * @param expiryCron when {@code scheduler/ChangeRequestExpiryJob} expires due requests ("-" off)
 * @param expiryBatch most requests expired per run
 * @param unlockCommandTtl {@code expiresAt - issuedAt} of the {@code UnlockWallet} an approval
 *     sends (ticket 52); the core drops it unseen after that, so it covers an outbox backlog
 */
@Validated
@ConfigurationProperties("mw.change-requests")
public record ChangeRequestProperties(
    @NotNull @DefaultValue("72h") Duration ttl,
    @NotNull @DefaultValue("0 */5 * * * *") String expiryCron,
    @DefaultValue("500") @Min(1) @Max(10_000) int expiryBatch,
    @NotNull @DefaultValue("1h") Duration unlockCommandTtl) {}
