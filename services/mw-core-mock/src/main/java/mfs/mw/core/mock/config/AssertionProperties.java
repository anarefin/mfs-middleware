package mfs.mw.core.mock.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code mw.coremock.assertion.*}: checking mw-auth's transaction assertions (spec 18.7, D22; ticket
 * 71).
 *
 * @param jwksUri mw-auth's {@code GET /auth/v1/.well-known/assertion-jwks.json} (public port, plain
 *     HTTP inside the network), fetched at start and every {@code refreshPeriod}, never on a command
 * @param issuer required {@code iss}
 * @param audience required in {@code aud}
 * @param clockSkew allowed past {@code exp} (and before {@code iat})
 * @param refreshPeriod how often the key set is fetched again (a failure keeps the last set)
 * @param timeout connect and read timeout of that fetch
 * @param usedRetention how long a used jti is kept after its {@code exp}
 * @param purgeCron when expired jtis are purged ({@code "-"} = never)
 * @param purgeBatchSize rows per purge statement
 */
@Validated
@ConfigurationProperties("mw.coremock.assertion")
public record AssertionProperties(
    @NotNull URI jwksUri,
    @NotBlank String issuer,
    @NotBlank String audience,
    @NotNull Duration clockSkew,
    @NotNull Duration refreshPeriod,
    @NotNull Duration timeout,
    @NotNull Duration usedRetention,
    @NotBlank String purgeCron,
    @Positive int purgeBatchSize) {}
