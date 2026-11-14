package mfs.mw.auth.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code mw.auth.customer-jwt}: the app's bearer token on {@code /auth/v1/**} until mw-auth issues
 * its own (ticket 69): a Keycloak realm {@code customers} token, checked as mw-app-gateway checks
 * it (signature from the realm's JWKS, expiry, the realm path at the end of {@code iss}).
 *
 * @param jwkSetUri the realm's signing keys, fetched on the first token
 * @param realmPath {@code /realms/customers}
 */
@Validated
@ConfigurationProperties("mw.auth.customer-jwt")
public record CustomerJwtProperties(@NotBlank String jwkSetUri, @NotBlank String realmPath) {}
