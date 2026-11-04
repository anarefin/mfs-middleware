package mfs.mw.simulators.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Outbox and inbox retention and the purge job (spec 6.4, 16.3), all under {@code mw.retention}:
 * sent outbox rows older than {@code outbox} and inbox rows older than {@code inbox} are deleted
 * on the {@code cron} schedule, {@code batch-size} rows per statement. Defaults in {@code
 * application.yml} (7 days each, hourly).
 *
 * @param outbox how long sent outbox rows are kept
 * @param inbox how long inbox rows are kept
 * @param batchSize rows deleted per statement
 * @param cron Spring cron for the purge job ({@code -} switches it off)
 */
@Validated
@ConfigurationProperties("mw.retention")
public record RetentionProperties(
    @NotNull Duration outbox, @NotNull Duration inbox, @Positive int batchSize, @NotBlank String cron) {}
