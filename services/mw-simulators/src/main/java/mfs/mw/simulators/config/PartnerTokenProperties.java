package mfs.mw.simulators.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code sim.partner-token.*} (ticket 100b): how the simulator asks Keycloak realm {@code partners}
 * for a client-credentials token. The private key is Vault {@code secret/mw-simulators} field
 * {@code sim.partner-sim.private-key} (provision, ticket 73). Never a client secret, never logged.
 */
@ConfigurationProperties("sim.partner-token")
public record PartnerTokenProperties(
    @DefaultValue("partner-sim") String clientId,
    @DefaultValue("http://keycloak:8080/realms/partners/protocol/openid-connect/token") String tokenUri,
    @DefaultValue("") String privateKey) {

  @Override
  public String toString() {
    return "PartnerTokenProperties[clientId=" + clientId + ", tokenUri=" + tokenUri + ", privateKey="
        + (privateKey == null || privateKey.isBlank() ? "absent" : "****") + "]";
  }
}
