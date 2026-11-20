package mfs.mw.adapter.atm.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The inbound ATM SOAP API (ticket 120a). {@code wsPassword} is the switch's WS-Security
 * UsernameToken password, bound from Vault ({@code secret/mw-adapter-atm}, field
 * {@code mw.atm.inbound.ws-password}); never in a config file. Without it every call is refused.
 *
 * @param partnerId the partner the switch is (the inbound events name it)
 * @param wsUsername the UsernameToken user name the switch sends
 * @param wsPassword the UsernameToken password (PasswordText, over TLS at the edge)
 */
@ConfigurationProperties("mw.atm.inbound")
public record AtmInboundProperties(String partnerId, String wsUsername, String wsPassword) {

  public AtmInboundProperties {
    partnerId = partnerId == null || partnerId.isBlank() ? "ucbatm" : partnerId;
    wsUsername = wsUsername == null || wsUsername.isBlank() ? "ucbatm" : wsUsername;
  }
}
