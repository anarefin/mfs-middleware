package mfs.mw.orchestrator.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code mw.internal-auth}: who may call {@code /internal/v1/**} (spec 18.2, M3 gate decision). A
 * Keycloak client-credentials JWT of realm {@code services}, checked for signature, expiry, exact
 * issuer and audience, from one of the allowed clients ({@code azp}).
 *
 * @param jwkSetUri the realm's signing keys, fetched lazily on the first token
 * @param issuer exact {@code iss} (services fetch their tokens from Keycloak directly, so it is fixed)
 * @param audience this service's name, required in {@code aud}
 * @param allowedClients client ids ({@code azp}) allowed to call
 * @param orgScopedClients allowed clients that must always send a non-blank {@code orgId}
 * @param revealClients allowed clients that may ask for unmasked MSISDNs ({@code reveal=true},
 *     ticket 36: mw-admin-api, which audits every reveal and grants it only to the roles of spec 15.4)
 * @param walletScopedClients allowed clients that may only search one wallet's history (ADR D25:
 *     mw-app-gateway): {@code GET /internal/v1/transactions} with a non-blank {@code walletId} and
 *     allow-listed query parameters only
 */
@Validated
@ConfigurationProperties("mw.internal-auth")
public record InternalAuthProperties(
    @NotBlank String jwkSetUri,
    @NotBlank String issuer,
    @NotBlank String audience,
    @NotEmpty Set<String> allowedClients,
    Set<String> orgScopedClients,
    Set<String> revealClients,
    Set<String> walletScopedClients) {

  public InternalAuthProperties {
    allowedClients = Set.copyOf(allowedClients == null ? Set.of() : allowedClients);
    orgScopedClients = Set.copyOf(orgScopedClients == null ? Set.of() : orgScopedClients);
    revealClients = Set.copyOf(revealClients == null ? Set.of() : revealClients);
    walletScopedClients = Set.copyOf(walletScopedClients == null ? Set.of() : walletScopedClients);
  }
}
