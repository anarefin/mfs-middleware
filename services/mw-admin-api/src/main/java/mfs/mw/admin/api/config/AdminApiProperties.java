package mfs.mw.admin.api.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * {@code mw.admin-api.*}: the Keycloak realm whose operator tokens are accepted (realm {@code ops},
 * spec 15.1, 18.2), and the internal query APIs this service reads (ticket 36): mw-orchestrator's
 * {@code txn-query.v1} and mw-audit-dw's {@code audit-query.v1}, both over the {@code app} network
 * with this service's client-credentials token of realm {@code services}.
 *
 * @param jwt operator tokens
 * @param internalAuth the client-credentials identity for {@code /internal/v1/**} (M3 gate decision)
 * @param orchestrator mw-orchestrator, e.g. {@code http://mw-orchestrator:8080}
 * @param auditDw mw-audit-dw, e.g. {@code http://mw-audit-dw:8080}
 * @param auditTrailLimit most audit events shown with one transaction (audit-query caps at 500)
 */
@Validated
@ConfigurationProperties("mw.admin-api")
public record AdminApiProperties(
    @Valid @NotNull Jwt jwt,
    @Valid @NotNull InternalAuth internalAuth,
    @Valid @NotNull Upstream orchestrator,
    @Valid @NotNull Upstream auditDw,
    @NotNull @DefaultValue("2s") Duration connectTimeout,
    @NotNull @DefaultValue("5s") Duration readTimeout,
    @DefaultValue("500") @Min(1) @Max(500) int auditTrailLimit) {

  /**
   * @param jwkSetUri the realm's signing keys, fetched lazily on the first token
   * @param realmPath required ending of the token's {@code iss} (the host depends on the URL used)
   */
  public record Jwt(@NotBlank String jwkSetUri, @NotBlank String realmPath) {}

  /** @param baseUrl scheme, host and port of the service's internal API */
  public record Upstream(@NotNull URI baseUrl) {}

  /**
   * Keycloak realm {@code services} client {@code mw-admin-api} (client credentials); its token
   * carries both audiences, mw-orchestrator and mw-audit-dw.
   *
   * @param tokenUri the realm's token endpoint
   * @param clientId the client id ({@code azp} of the token)
   * @param clientSecret from OpenBao ({@code secret/mw-admin-api}), never in config files
   */
  public record InternalAuth(@NotNull URI tokenUri, @NotBlank String clientId, @NotBlank String clientSecret) {

    @Override
    public String toString() {
      return "InternalAuth[tokenUri=" + tokenUri + ", clientId=" + clientId + ", clientSecret=****]";
    }
  }
}
