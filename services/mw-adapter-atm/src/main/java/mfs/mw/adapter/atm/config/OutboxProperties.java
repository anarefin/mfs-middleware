package mfs.mw.adapter.atm.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Outbox relay (spec 6.4): whether the relay schedule ({@code scheduler/OutboxRelayScheduler})
 * runs, how long it waits after a run before looking for unsent rows again, and how many rows it
 * publishes per transaction. Defaults in {@code application.yml}.
 */
@Validated
@ConfigurationProperties("mw.outbox.relay")
public record OutboxProperties(boolean enabled, @NotNull Duration period, @Positive int batchSize) {}
