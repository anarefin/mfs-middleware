package mfs.mw.app.gateway.config;

import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code mw.app-gateway.billpay.*}: the internal API of mw-adapter-billpay (biller catalogue and
 * bill fetch; ticket 21), reached over the {@code app} zone network only.
 *
 * @param baseUrl the adapter's internal address, e.g. {@code http://mw-adapter-billpay:8080}
 * @param connectTimeout TCP connect limit
 * @param readTimeout answer limit; above the adapter's partner time limit so the adapter decides
 * @param catalogueTtl how long the biller list is reused before it is asked again
 */
@Validated
@ConfigurationProperties("mw.app-gateway.billpay")
public record BillPayProperties(
    @NotNull URI baseUrl,
    @NotNull Duration connectTimeout,
    @NotNull Duration readTimeout,
    @NotNull Duration catalogueTtl) {}
