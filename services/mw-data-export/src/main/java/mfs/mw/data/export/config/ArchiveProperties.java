package mfs.mw.data.export.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Online retention and the archive of the batch facts (ticket 128), under {@code mw.archive}. A
 * day older than {@code online} is gzipped, indexed and removed. An archive older than {@code
 * keep} is deleted.
 */
@Validated
@ConfigurationProperties("mw.archive")
public record ArchiveProperties(
    @NotBlank String directory,
    @NotBlank String cron,
    @NotBlank String zone,
    @NotNull Windows online,
    @NotNull Windows keep) {

  /** One duration per stored type. */
  public record Windows(
      @NotNull Duration transactions, @NotNull Duration notifications, @NotNull Duration recon) {}
}
