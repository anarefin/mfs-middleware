package mfs.mw.app.gateway.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code mw.app-gateway.auth}: mw-auth's internal API (ticket 68; spec 18.7, D24), asked by the
 * device gate for a device's public key and the wallet's session: over mTLS with this service's
 * OpenBao certificate and a Keycloak realm {@code services} client-credentials JWT.
 *
 * @param baseUrl mw-auth's internal port ({@code https://mw-auth:8443})
 * @param tokenUri the realm's token endpoint (plain HTTP inside the network in dev)
 * @param clientId this service's client ({@code mw-app-gateway}, equal to its certificate CN)
 * @param clientSecret from OpenBao {@code secret/mw-app-gateway} only, never in a file
 * @param timeout per call (2 s; a slower answer fails closed)
 * @param connectTimeout TCP and TLS connect
 * @param pinKeyUri mw-auth's public {@code GET /auth/v1/pin-key} (plain HTTP inside the network, no
 *     token; ticket 72), served to the apps as {@code GET /app/v1/pin-key}
 * @param pinKeyTtl how long the key is reused before mw-auth is asked again
 */
@Validated
@ConfigurationProperties("mw.app-gateway.auth")
public record AuthClientProperties(
    @NotNull URI baseUrl,
    @NotNull URI tokenUri,
    @NotBlank String clientId,
    String clientSecret,
    @NotNull Duration timeout,
    @NotNull Duration connectTimeout,
    @NotNull URI pinKeyUri,
    @NotNull Duration pinKeyTtl) {

  public AuthClientProperties {
    clientSecret = clientSecret == null ? "" : clientSecret;
  }

  @Override
  public String toString() {
    return "AuthClientProperties[baseUrl=" + baseUrl + ", tokenUri=" + tokenUri + ", clientId=" + clientId
        + ", clientSecret=****, timeout=" + timeout + ", connectTimeout=" + connectTimeout
        + ", pinKeyUri=" + pinKeyUri + ", pinKeyTtl=" + pinKeyTtl + "]";
  }
}
