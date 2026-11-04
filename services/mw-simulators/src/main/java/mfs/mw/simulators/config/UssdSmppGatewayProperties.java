package mfs.mw.simulators.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * USSD gateway simulator, SMPP mode (spec 22.2, ticket 27): an SMPP 3.4 server mw-ussd binds to
 * as transceiver for the MNOs whose USSD runs over SMPP.
 *
 * @param enabled start the SMPP listener
 * @param port SMPP port (2776; the SMSC simulator has 2775)
 * @param mnos MNOs this simulator plays over SMPP; any other MNO is played over HTTP
 * @param screenTimeout how long a played step waits for mw-ussd's submit_sm
 */
@ConfigurationProperties("sim.ussd-gateway.smpp")
public record UssdSmppGatewayProperties(boolean enabled, int port, List<String> mnos, Duration screenTimeout) {

  public UssdSmppGatewayProperties {
    port = port == 0 ? 2776 : port;
    mnos = List.copyOf(mnos == null ? List.of("gp") : mnos);
    screenTimeout = screenTimeout == null ? Duration.ofSeconds(15) : screenTimeout;
  }
}
