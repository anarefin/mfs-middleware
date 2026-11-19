package mfs.mw.simulators.config;

import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Where the global reset (FRS MCT-07, ticket 103) reaches mw-core-mock's mock control, {@code
 * sim.core-mock}.
 *
 * @param baseUrl mw-core-mock inside the app network
 * @param timeout how long its reset (ledger back to the seed) may take
 */
@Validated
@ConfigurationProperties("sim.core-mock")
public record CoreMockControlProperties(@NotBlank String baseUrl, Duration timeout) {

  public CoreMockControlProperties {
    baseUrl = baseUrl == null || baseUrl.isBlank() ? "http://mw-core-mock:8080" : baseUrl;
    timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
  }
}
