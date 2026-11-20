package mfs.mw.orchestrator.config;

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
 * @param transactions how long a terminal transaction is kept (spec 17, about 13 months)
 * @param sagaSteps how long a step of a terminal transaction is kept
 * @param partnerCalls how long a partner call of a terminal transaction is kept
 * @param coreCalls how long a core call of a terminal transaction is kept
 * @param idempotency the idempotency window; rows are deleted once {@code expires_at} has passed
 */
@Validated
@ConfigurationProperties("mw.retention")
public record RetentionProperties(
    @NotNull Duration outbox,
    @NotNull Duration inbox,
    @Positive int batchSize,
    @NotBlank String cron,
    @NotNull Duration transactions,
    @NotNull Duration sagaSteps,
    @NotNull Duration partnerCalls,
    @NotNull Duration coreCalls,
    @NotNull Duration idempotency) {}
