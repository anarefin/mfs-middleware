package mfs.mw.admin.api.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * {@code mw.apisix.*}: the APISIX Admin API the channel client registry is projected to (ticket
 * 100a). Off unless {@code enabled} (the stack sets {@code MW_APISIX_SYNC_ENABLED=true}); this
 * service then joins the {@code ops} network, the only one the Admin API accepts.
 *
 * @param adminUrl e.g. {@code http://apisix:9180}
 * @param adminKey the Admin API key, from Vault ({@code secret/mw-admin-api}, synced from
 *     {@code APISIX_ADMIN_KEY}), never in config files
 * @param secretRef the APISIX secret reference prefix a consumer's credential is read under, e.g.
 *     {@code $secret://vault/edge}: a BASIC client's password is {@code <secretRef>/<clientId>/password}
 *     (Vault KV v1 mount {@code apisix/}, read by APISIX with its read-only token)
 * @param redis where the {@code limit-count} daily quota counts (the routes' Redis)
 */
@Validated
@ConfigurationProperties("mw.apisix")
public record ApisixProperties(
    @DefaultValue("false") boolean enabled,
    @NotNull @DefaultValue("http://apisix:9180") URI adminUrl,
    @DefaultValue("") String adminKey,
    @NotBlank @DefaultValue("$secret://vault/edge") String secretRef,
    @Valid @NotNull @DefaultValue Redis redis,
    @NotNull @DefaultValue("2s") Duration connectTimeout,
    @NotNull @DefaultValue("5s") Duration readTimeout) {

  /**
   * @param passwordRef an APISIX reference, never the password ({@code $env://REDIS_PASSWORD})
   */
  public record Redis(
      @NotBlank @DefaultValue("redis") String host,
      @DefaultValue("6379") int port,
      @NotBlank @DefaultValue("$env://REDIS_PASSWORD") String passwordRef) {}

  @Override
  public String toString() {
    return "ApisixProperties[enabled=" + enabled + ", adminUrl=" + adminUrl + ", adminKey=****, secretRef=" + secretRef
        + ", redis=" + redis + "]";
  }
}
