package mfs.mw.simulators.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Simulator settings under {@code sim} (spec 22.2).
 *
 * @param timeoutDelay how long a "timeout" scenario holds the reply (must exceed the caller's time limit)
 * @param recordLimit requests kept per mock party in the journal (oldest dropped first)
 */
@Validated
@ConfigurationProperties("sim")
public record SimulatorProperties(
    @NotNull Duration timeoutDelay, @Positive int recordLimit) {}
