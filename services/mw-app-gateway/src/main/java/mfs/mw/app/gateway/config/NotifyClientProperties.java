package mfs.mw.app.gateway.config;

import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code mw.app-gateway.notify.*}: mw-notify's customer inbox (ticket 107a), on the app network.
 *
 * @param baseUrl mw-notify, e.g. {@code http://mw-notify:8080}
 * @param connectTimeout TCP connect limit
 * @param readTimeout answer limit
 */
@Validated
@ConfigurationProperties("mw.app-gateway.notify")
public record NotifyClientProperties(
    @NotNull URI baseUrl, @NotNull Duration connectTimeout, @NotNull Duration readTimeout) {}
