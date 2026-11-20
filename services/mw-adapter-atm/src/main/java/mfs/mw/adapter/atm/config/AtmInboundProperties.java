package mfs.mw.adapter.atm.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The inbound ATM SOAP API (tickets 120a, 120b1). {@code wsPassword} is the switch's WS-Security
 * UsernameToken password, bound from Vault ({@code secret/mw-adapter-atm}, field
 * {@code mw.atm.inbound.ws-password}); never in a config file. Without it every call is refused.
 * It never crosses the wire: the switch sends a PasswordDigest.
 *
 * @param partnerId the partner the switch is (the inbound events name it)
 * @param wsUsername the UsernameToken user name the switch sends
 * @param wsPassword the UsernameToken password (the digest's shared secret)
 * @param cashEnabled whether VerifyTokenAndDebit and CashIn are served. Off by default (fail
 *     closed, M9 review): until the token check, the hold and the credit exist (tickets 120b2,
 *     120b3) both answer the SOAP fault {@code MW-SYS-ATM_CASH_DISABLED}. Only tests turn it on.
 */
@ConfigurationProperties("mw.atm.inbound")
public record AtmInboundProperties(String partnerId, String wsUsername, String wsPassword, Boolean cashEnabled) {

  public AtmInboundProperties {
    partnerId = partnerId == null || partnerId.isBlank() ? "ucbatm" : partnerId;
    wsUsername = wsUsername == null || wsUsername.isBlank() ? "ucbatm" : wsUsername;
    cashEnabled = Boolean.TRUE.equals(cashEnabled);
  }
}
