package mfs.mw.adapter.bank.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Service identity, bound from {@code application.yml}, mounted overrides and, when {@code
 * VAULT_ENABLED=true}, secrets in OpenBao at {@code secret/<spring.application.name>} (spec
 * 18.4, 20.4). Add secret-backed settings (partner credentials, keys) as further properties
 * classes in this package; never put their values in config files.
 */
@Validated
@ConfigurationProperties("mw.service")
public record MwServiceProperties(@NotBlank String name, @NotBlank String version, @NotBlank String env) {}
