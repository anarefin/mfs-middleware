package mfs.mw.auth.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code mw.auth.core.*}: the core wallet API (ADR D31, ticket 95b; {@code client/CoreClient}), for the
 * PIN reset's KYC factors check. The service token is this service's realm {@code services} token
 * ({@code client/ServiceTokenClient}), whose {@code aud} carries {@code mw-core-mock} as well.
 *
 * @param baseUrl the core ({@code http://mw-core-mock:8080})
 * @param timeout read timeout of one call
 * @param connectTimeout connect timeout
 * @param attempts tries of the factors check (idempotent by txnId) before its outcome is unknown
 * @param backoff wait between tries
 */
@Validated
@ConfigurationProperties("mw.auth.core")
public record CoreClientProperties(
    @NotNull URI baseUrl,
    @NotNull Duration timeout,
    @NotNull Duration connectTimeout,
    @Min(1) @Max(10) int attempts,
    @NotNull Duration backoff) {}
