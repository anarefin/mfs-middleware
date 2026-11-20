package mfs.mw.admin.api.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * {@code mw.core.*}: the core wallet API (ADR D31, ticket 95b; {@code client/CoreAccountClient}), for an
 * approved ops unlock. The service token is this service's realm {@code services} token ({@code
 * client/ServiceTokenClient}), whose {@code aud} carries {@code mw-core-mock} as well.
 *
 * @param baseUrl the core ({@code http://mw-core-mock:8080})
 * @param timeout read timeout of one call
 * @param connectTimeout connect timeout
 * @param attempts tries of the unlock (idempotent by txnId) before its outcome is unknown
 * @param backoff wait between tries
 */
@Validated
@ConfigurationProperties("mw.core")
public record CoreClientProperties(
    @NotNull URI baseUrl,
    @NotNull @DefaultValue("2s") Duration timeout,
    @NotNull @DefaultValue("1s") Duration connectTimeout,
    @DefaultValue("3") @Min(1) @Max(10) int attempts,
    @NotNull @DefaultValue("300ms") Duration backoff) {}
