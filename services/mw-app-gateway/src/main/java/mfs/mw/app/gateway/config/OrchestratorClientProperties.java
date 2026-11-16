package mfs.mw.app.gateway.config;

import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * {@code mw.app-gateway.orchestrator.*}: mw-orchestrator's internal query API (contract {@code
 * api/mw-orchestrator/txn-query.v1.openapi.yaml}, ADR D25), asked for the signed-in wallet's
 * history over the {@code app} network with this service's client-credentials token ({@code
 * client/ServiceTokenClient}, the same Keycloak client {@code mw-app-gateway} as for mw-auth; its
 * token carries both audiences).
 *
 * @param baseUrl e.g. {@code http://mw-orchestrator:8080}
 * @param connectTimeout TCP connect limit
 * @param readTimeout answer limit per call (a slower answer is 503 MW-SYS-013)
 */
@Validated
@ConfigurationProperties("mw.app-gateway.orchestrator")
public record OrchestratorClientProperties(
    @NotNull URI baseUrl,
    @NotNull @DefaultValue("1s") Duration connectTimeout,
    @NotNull @DefaultValue("3s") Duration readTimeout) {}
