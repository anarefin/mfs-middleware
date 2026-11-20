package mfs.mw.app.gateway.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * {@code mw.app-gateway.preview.*}: the two-step financial API (ticket 105, FRS CH-APP-05, PLT-026;
 * {@code service/PreviewService}).
 *
 * @param ttl how long a preview can be confirmed (5 min); also its Redis TTL
 * @param signingKey HMAC-SHA256 key of the previewId (at least 32 characters; from Vault, {@code
 *     secret/mw-app-gateway}, created once by the dev provisioning; never in a config file)
 */
@Validated
@ConfigurationProperties("mw.app-gateway.preview")
public record PreviewProperties(
    @NotNull @DefaultValue("5m") Duration ttl, @NotNull @Size(min = 32) String signingKey) {

  @Override
  public String toString() {
    return "PreviewProperties[ttl=" + ttl + ", signingKey=****]";
  }
}
