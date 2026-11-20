package mfs.mw.admin.api.config;

import jakarta.validation.constraints.NotNull;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * {@code mw.recon.*}: mw-recon's exception work queue ({@code api/mw-recon/recon.v1}, ticket 127b;
 * {@code client/ReconExceptionClient}) for the {@code recon_exception} change requests.
 *
 * @param baseUrl mw-recon ({@code http://mw-recon:8080}, env {@code MW_RECON_BASE_URL})
 */
@Validated
@ConfigurationProperties("mw.recon")
public record ReconClientProperties(@NotNull @DefaultValue("http://mw-recon:8080") URI baseUrl) {}
