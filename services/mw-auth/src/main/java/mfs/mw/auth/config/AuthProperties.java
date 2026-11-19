package mfs.mw.auth.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code mw.auth}: this service's settings (defaults in {@code application.yml}).
 *
 * @param crypto the mw-crypto client ({@code client/CryptoClient})
 */
@Validated
@ConfigurationProperties("mw.auth")
public record AuthProperties(@Valid @NotNull Crypto crypto) {

  /**
   * mw-crypto over mTLS with a Keycloak realm {@code services} client-credentials JWT (spec 18.7,
   * 18.8, D24).
   *
   * @param baseUrl mw-crypto's API ({@code https://mw-crypto:8080})
   * @param tokenUri the realm's token endpoint (plain HTTP inside the network in dev)
   * @param clientId this service's client ({@code mw-auth}, equal to its certificate CN)
   * @param clientSecret from Vault {@code secret/mw-auth} only, never in a file
   * @param timeout per call (spec 18.7: 2 s; a slower answer fails closed)
   * @param connectTimeout TCP and TLS connect
   */
  public record Crypto(
      @NotNull URI baseUrl,
      @NotNull URI tokenUri,
      @NotBlank String clientId,
      String clientSecret,
      @NotNull Duration timeout,
      @NotNull Duration connectTimeout) {

    public Crypto {
      clientSecret = clientSecret == null ? "" : clientSecret;
    }

    @Override
    public String toString() {
      return "Crypto[baseUrl=" + baseUrl + ", tokenUri=" + tokenUri + ", clientId=" + clientId
          + ", clientSecret=****, timeout=" + timeout + ", connectTimeout=" + connectTimeout + "]";
    }
  }
}
