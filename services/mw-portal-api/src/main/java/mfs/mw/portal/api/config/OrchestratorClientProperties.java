package mfs.mw.portal.api.config;

import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * mw-orchestrator's internal query API under {@code mw.portal-api.orchestrator} (ticket 35,
 * contract {@code api/mw-orchestrator/txn-query.v1.openapi.yaml}), over the {@code app} network.
 *
 * @param baseUrl e.g. {@code http://mw-orchestrator:8080}
 */
@Validated
@ConfigurationProperties("mw.portal-api.orchestrator")
public record OrchestratorClientProperties(
    @NotNull URI baseUrl,
    @NotNull @DefaultValue("2s") Duration connectTimeout,
    @NotNull @DefaultValue("5s") Duration readTimeout) {}
