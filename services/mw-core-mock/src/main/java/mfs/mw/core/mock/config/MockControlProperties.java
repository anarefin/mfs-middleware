package mfs.mw.core.mock.config;

import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The core's mock-control engine (ADR D33, ticket 103), {@code mw.coremock.mock-control}; used only in
 * the {@code mock-control} profile.
 *
 * @param timeoutDelay how long a TIMEOUT scenario holds the answer when it names no {@code delayMs}
 *     (default 30 s: above the callers' time limits)
 * @param journalLimit how many commands the journal keeps
 */
@Validated
@ConfigurationProperties("mw.coremock.mock-control")
public record MockControlProperties(Duration timeoutDelay, @Positive Integer journalLimit) {

  public MockControlProperties {
    timeoutDelay = timeoutDelay == null ? Duration.ofSeconds(30) : timeoutDelay;
    journalLimit = journalLimit == null ? 1000 : journalLimit;
  }
}
