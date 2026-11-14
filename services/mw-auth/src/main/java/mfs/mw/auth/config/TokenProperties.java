package mfs.mw.auth.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code mw.auth.tokens.*}: the wallet users' tokens (spec 18.7, D21; ticket 69).
 *
 * @param issuer {@code iss} of the access JWT and of the discovery document the edge reads
 *     ({@code openid-connect} compares the two exactly)
 * @param audience {@code aud} of the access JWT (the APISIX route's {@code client_id}; mw-app-gateway
 *     checks it too)
 * @param accessTtl access JWT lifetime, 5 min
 * @param refreshTtl refresh token lifetime, 30 days
 * @param jwksCacheTtl how long the JWT_SIGN and PIN_TRANSPORT key sets from mw-crypto are reused (an
 *     unknown kid refreshes them at once)
 * @param jwksUri the JWK Set URL the discovery document names (in-stack, as APISIX reaches it)
 */
@Validated
@ConfigurationProperties("mw.auth.tokens")
public record TokenProperties(
    @NotBlank String issuer,
    @NotBlank String audience,
    @NotNull Duration accessTtl,
    @NotNull Duration refreshTtl,
    @NotNull Duration jwksCacheTtl,
    @NotBlank String jwksUri) {}
