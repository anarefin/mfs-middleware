package mfs.mw.portal.api.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * mw-orchestrator's internal query API under {@code mw.portal-api.orchestrator} (ticket 35,
 * contract {@code api/mw-orchestrator/txn-query.v1.openapi.yaml}), over the {@code app} network.
 *
 * @param baseUrl e.g. {@code http://mw-orchestrator:8080}
 * @param auth the client-credentials identity this service calls it with (M3 gate decision)
 */
@Validated
@ConfigurationProperties("mw.portal-api.orchestrator")
public record OrchestratorClientProperties(
    @NotNull URI baseUrl,
    @NotNull @DefaultValue("2s") Duration connectTimeout,
    @NotNull @DefaultValue("5s") Duration readTimeout,
    @Valid @NotNull Auth auth) {

  /**
   * Keycloak realm {@code services} client {@code mw-portal-api} (client credentials).
   *
   * @param tokenUri the realm's token endpoint
   * @param clientId the client id ({@code azp} of the token)
   * @param clientSecret from OpenBao ({@code secret/mw-portal-api}), never in config files
   */
  public record Auth(@NotNull URI tokenUri, @NotBlank String clientId, @NotBlank String clientSecret) {

    @Override
    public String toString() {
      return "Auth[tokenUri=" + tokenUri + ", clientId=" + clientId + ", clientSecret=****]";
    }
  }
}
