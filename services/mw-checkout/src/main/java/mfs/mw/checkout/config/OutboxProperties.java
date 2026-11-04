package mfs.mw.checkout.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Outbox relay (spec 6.4): whether the relay route runs, how often it looks for unsent rows and
 * how many rows it publishes per transaction. Defaults in {@code application.yml}.
 */
@Validated
@ConfigurationProperties("mw.outbox.relay")
public record OutboxProperties(boolean enabled, @NotNull Duration period, @Positive int batchSize) {}
