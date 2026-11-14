package mfs.mw.crypto.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code mw.internal-auth}: who may call {@code /internal/v1/**} (spec 18.2, 18.8; copied from
 * mw-audit-dw). A Keycloak client-credentials JWT of realm {@code services}, checked for signature,
 * expiry, exact issuer and audience, from one of the allowed clients ({@code azp}).
 *
 * @param jwkSetUri the realm's signing keys, fetched lazily on the first token
 * @param issuer exact {@code iss} (services fetch their tokens from Keycloak directly, so it is fixed)
 * @param audience this service's name, required in {@code aud}
 * @param allowedClients client ids ({@code azp}) allowed to call
 * @param keyAdminClients the allowed clients that may also generate and rotate keys (security review
 *     of ticket 62, done in ticket 66; default {@code mw-admin-api}); empty = nobody
 */
@Validated
@ConfigurationProperties("mw.internal-auth")
public record InternalAuthProperties(
    @NotBlank String jwkSetUri,
    @NotBlank String issuer,
    @NotBlank String audience,
    @NotEmpty Set<String> allowedClients,
    Set<String> keyAdminClients) {

  public InternalAuthProperties {
    allowedClients = Set.copyOf(allowedClients == null ? Set.of() : allowedClients);
    keyAdminClients = Set.copyOf(keyAdminClients == null ? Set.of() : keyAdminClients);
  }
}
