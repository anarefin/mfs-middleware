package mfs.mw.simulators.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SMSC simulator (spec 22.2, ticket 24): SMPP 3.4 server for mw-notify.
 *
 * @param enabled start the SMPP listener
 * @param port SMPP port (2775 by convention)
 * @param dlrDelay how long after the submit_sm_resp the delivery receipt goes out
 */
@ConfigurationProperties("sim.smsc")
public record SmscProperties(boolean enabled, int port, Duration dlrDelay) {

  public SmscProperties {
    port = port == 0 ? 2775 : port;
    dlrDelay = dlrDelay == null ? Duration.ofMillis(300) : dlrDelay;
  }
}
