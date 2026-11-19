package mfs.mw.ussd.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code mw.ussd.auth}: mw-auth (ticket 72; spec 18.7, D22, D24). The transaction authorisation on
 * its internal port over mTLS with this service's Vault certificate and a Keycloak realm {@code
 * services} client-credentials JWT; the PIN transport key on its public port (no token).
 *
 * @param baseUrl mw-auth's internal port ({@code https://mw-auth:8443})
 * @param tokenUri the realm's token endpoint (plain HTTP inside the network in dev)
 * @param clientId this service's client ({@code mw-ussd}, equal to its certificate CN)
 * @param clientSecret from Vault {@code secret/mw-ussd} only, never in a file
 * @param timeout per call (2 s; a slower answer fails closed)
 * @param connectTimeout TCP and TLS connect
 * @param pinKeyTtl how long a fetched transport key (mw-auth {@code GET /internal/v1/pin-key} on
 *     {@code baseUrl}, over mTLS; security review of ticket 72) is used before it is asked again
 */
@Validated
@ConfigurationProperties("mw.ussd.auth")
public record AuthClientProperties(
    @NotNull URI baseUrl,
    @NotNull URI tokenUri,
    @NotBlank String clientId,
    String clientSecret,
    @NotNull Duration timeout,
    @NotNull Duration connectTimeout,
    @NotNull Duration pinKeyTtl) {

  public AuthClientProperties {
    clientSecret = clientSecret == null ? "" : clientSecret;
  }

  @Override
  public String toString() {
    return "AuthClientProperties[baseUrl=" + baseUrl + ", tokenUri=" + tokenUri + ", clientId=" + clientId
        + ", clientSecret=****, timeout=" + timeout + ", connectTimeout=" + connectTimeout
        + ", pinKeyTtl=" + pinKeyTtl + "]";
  }
}
