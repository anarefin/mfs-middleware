package mfs.mw.service.template.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Outbox and inbox retention and the purge job (spec 6.4, 16.3): sent outbox rows older than
 * {@code mw.outbox.retention} and inbox rows older than {@code mw.inbox.retention} are deleted
 * every {@code mw.purge.period}, {@code mw.purge.batch-size} rows per statement. Defaults in
 * {@code application.yml} (7 days each).
 */
@Validated
@ConfigurationProperties("mw")
public record RetentionProperties(
    @Valid @NotNull Retention outbox, @Valid @NotNull Retention inbox, @Valid @NotNull Purge purge) {

  /** How long processed rows are kept. */
  public record Retention(@NotNull Duration retention) {}

  /** The purge job's schedule and batch size. */
  public record Purge(@NotNull Duration period, @Positive int batchSize) {}
}
