package mfs.mw.portal.api.config;

import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The core wallet API under {@code mw.portal-api.core} (ticket 129c, contract {@code
 * api/mw-core-mock/core-wallet.v1.openapi.yaml}): only the org wallet's balance, read with the
 * same client-credentials token as the orchestrator's query API ({@code mw.portal-api.orchestrator.auth}).
 *
 * @param baseUrl e.g. {@code http://mw-core-mock:8080}
 * @param balanceCheck whether a batch's org wallet balance is checked at approval and at dispatch
 *     start (tests without a core switch it off)
 */
@Validated
@ConfigurationProperties("mw.portal-api.core")
public record CoreClientProperties(
    @NotNull URI baseUrl,
    @NotNull @DefaultValue("1s") Duration connectTimeout,
    @NotNull @DefaultValue("3s") Duration readTimeout,
    @DefaultValue("true") boolean balanceCheck) {}
