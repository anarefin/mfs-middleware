package mfs.mw.simulators.config;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * USSD gateway simulator, SMPP mode (spec 22.2, ticket 27, CH-USSD-01): an SMPP 3.4 server on the
 * Grameenphone port, plus one port per other MNO when {@code peers-enabled} is set. mw-ussd binds
 * as transceiver or as transmitter + receiver. A dial for an MNO in {@code mnos} is played on that
 * MNO's port; any other MNO is played over HTTP.
 *
 * @param enabled start the listeners
 * @param port Grameenphone SMPP port (2776; the SMSC simulator has 2775). Tests override this
 * @param mnos MNOs played over SMPP
 * @param screenTimeout how long a played step waits for mw-ussd's submit_sm
 * @param party mock-control party of the Grameenphone port ({@code ussd-gp}; {@code ussdgw} remains
 *     the legacy party a default decision falls back to)
 * @param packing session-info layout of that port ({@code EOS_BIT0} or {@code EOS_BIT7})
 * @param peersEnabled also listen for robi, bl and teletalk
 * @param peers those MNOs' ports, parties and packing
 */
@ConfigurationProperties("sim.ussd-gateway.smpp")
public record UssdSmppGatewayProperties(
    boolean enabled,
    int port,
    List<String> mnos,
    Duration screenTimeout,
    String party,
    String packing,
    boolean peersEnabled,
    Map<String, Peer> peers) {

  /**
   * One MNO's SMPP listener besides Grameenphone.
   *
   * @param listen false keeps the port closed (tests, so cached contexts do not fight over it)
   */
  public record Peer(int port, String party, String packing, Boolean listen) {

    public Peer {
      packing = packing == null || packing.isBlank() ? "EOS_BIT0" : packing;
      listen = listen == null || listen;
    }
  }

  public UssdSmppGatewayProperties {
    port = port == 0 ? 2776 : port;
    mnos = List.copyOf(mnos == null ? List.of("gp") : mnos);
    screenTimeout = screenTimeout == null ? Duration.ofSeconds(15) : screenTimeout;
    party = party == null || party.isBlank() ? "ussdgw" : party;
    packing = packing == null || packing.isBlank() ? "EOS_BIT0" : packing;
    peers = Map.copyOf(peers == null ? Map.of() : peers);
  }
}
