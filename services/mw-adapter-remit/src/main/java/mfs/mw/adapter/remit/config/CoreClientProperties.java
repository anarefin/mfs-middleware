package mfs.mw.adapter.remit.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code mw.remit.core}: the core wallet API the beneficiary lookup reads (ticket 119c, ADR D31, WAL-01
 * {@code GET /core/v1/accounts/{msisdn}}) with a Keycloak realm {@code services} client-credentials JWT
 * (client {@code mw-adapter-remit}, audience {@code mw-core-mock}).
 *
 * @param clientSecret from Vault {@code secret/mw-adapter-remit} only, never in a file
 */
@Validated
@ConfigurationProperties("mw.remit.core")
public record CoreClientProperties(
    @NotNull URI baseUrl,
    @NotNull URI tokenUri,
    @NotBlank String clientId,
    String clientSecret,
    @NotNull Duration connectTimeout,
    @NotNull Duration readTimeout) {

  public CoreClientProperties {
    clientSecret = clientSecret == null ? "" : clientSecret;
  }

  @Override
  public String toString() {
    return "CoreClientProperties[baseUrl=" + baseUrl + ", tokenUri=" + tokenUri + ", clientId=" + clientId
        + ", clientSecret=****, connectTimeout=" + connectTimeout + ", readTimeout=" + readTimeout + "]";
  }
}
