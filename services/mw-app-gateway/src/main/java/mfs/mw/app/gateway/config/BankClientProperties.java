package mfs.mw.app.gateway.config;

import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * {@code mw.app-gateway.bank.*}: mw-adapter-bank's account validation (contract {@code
 * api/mw-adapter-bank/bank.v1.openapi.yaml}, ticket 117b), asked over the app network with this
 * service's client-credentials token (audience {@code mw-adapter-bank}).
 *
 * @param baseUrl e.g. {@code http://mw-adapter-bank:8080}
 * @param connectTimeout TCP connect limit
 * @param readTimeout answer limit; above the adapter's 25 s partner limit so the adapter decides first
 */
@Validated
@ConfigurationProperties("mw.app-gateway.bank")
public record BankClientProperties(
    @NotNull URI baseUrl,
    @NotNull @DefaultValue("1s") Duration connectTimeout,
    @NotNull @DefaultValue("28s") Duration readTimeout) {}
