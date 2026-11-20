package mfs.mw.app.gateway.config;

import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * {@code mw.app-gateway.core.*}: mw-core-mock's wallet API (contract {@code
 * api/mw-core-mock/core-wallet.v1.openapi.yaml}, ticket 107b), asked for the signed-in wallet over
 * the app network with this service's client-credentials token (audience {@code mw-core-mock}).
 *
 * @param baseUrl e.g. {@code http://mw-core-mock:8080}
 * @param connectTimeout TCP connect limit
 * @param readTimeout answer limit per call
 */
@Validated
@ConfigurationProperties("mw.app-gateway.core")
public record CoreClientProperties(
    @NotNull URI baseUrl,
    @NotNull @DefaultValue("1s") Duration connectTimeout,
    @NotNull @DefaultValue("2s") Duration readTimeout) {}
