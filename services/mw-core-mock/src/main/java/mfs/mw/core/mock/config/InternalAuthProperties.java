package mfs.mw.core.mock.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code mw.internal-auth}: who may call the core wallet API {@code /core/v1/**} (spec 18.2, ADR
 * D31, ticket 93). A Keycloak client-credentials JWT of realm {@code services}, checked for
 * signature, expiry, exact issuer and audience, from one of the allowed clients ({@code azp}).
 *
 * @param jwkSetUri the realm's signing keys, fetched lazily on the first token
 * @param issuer exact {@code iss}
 * @param audience this service's name, required in {@code aud}
 * @param allowedClients client ids ({@code azp}) allowed to call
 */
@Validated
@ConfigurationProperties("mw.internal-auth")
public record InternalAuthProperties(
    @NotBlank String jwkSetUri, @NotBlank String issuer, @NotBlank String audience, @NotEmpty Set<String> allowedClients) {

  public InternalAuthProperties {
    allowedClients = Set.copyOf(allowedClients == null ? Set.of() : allowedClients);
  }
}
