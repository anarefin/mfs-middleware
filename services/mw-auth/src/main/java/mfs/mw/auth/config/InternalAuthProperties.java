package mfs.mw.auth.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code mw.internal-auth}: who may call {@code /internal/v1/**} (spec 18.2, 18.7, D24; copied from
 * mw-crypto). A Keycloak client-credentials JWT of realm {@code services}, checked for signature,
 * expiry, exact issuer and audience, from one of the allowed clients ({@code azp}), on the internal
 * (mTLS) port only.
 *
 * @param jwkSetUri the realm's signing keys, fetched lazily on the first token
 * @param issuer exact {@code iss} (services fetch their tokens from Keycloak directly, so it is fixed)
 * @param audience this service's name, required in {@code aud}
 * @param allowedClients client ids ({@code azp}) allowed to call: the channels mw-app-gateway,
 *     mw-ussd, mw-checkout and mw-admin-api (spec 18.7)
 */
@Validated
@ConfigurationProperties("mw.internal-auth")
public record InternalAuthProperties(
    @NotBlank String jwkSetUri, @NotBlank String issuer, @NotBlank String audience, @NotEmpty Set<String> allowedClients) {

  public InternalAuthProperties {
    allowedClients = Set.copyOf(allowedClients == null ? Set.of() : allowedClients);
  }
}
