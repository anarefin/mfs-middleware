package mfs.mw.admin.api.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * {@code mw.credentials.*}: partner credential upload and expiry alerts (ticket 131).
 *
 * @param vault where uploads are staged and written (the stack's Vault KV v2 mount {@code secret/})
 * @param expiry the expiry alerts of {@code scheduler/CredentialExpiryJob}
 */
@Validated
@ConfigurationProperties("mw.credentials")
public record CredentialProperties(@Valid @NotNull @DefaultValue Vault vault, @Valid @NotNull @DefaultValue Expiry expiry) {

  /**
   * @param enabled off: an upload is refused 503 MW-SYS-043 (tests without Vault); the stack follows
   *     {@code VAULT_ENABLED}
   * @param uri Vault's address
   * @param token a fixed token (tests only); blank: the Spring Cloud Vault session token (AppRole)
   * @param kvMount the KV version 2 mount holding the partners' secrets
   * @param stagingPath where an upload waits for its checker: {@code <kvMount>/<stagingPath>/<id>}
   *     (this service's own secret tree)
   */
  public record Vault(
      @DefaultValue("false") boolean enabled,
      @NotNull @DefaultValue("http://vault:8200") URI uri,
      @DefaultValue("") String token,
      @NotBlank @DefaultValue("secret") String kvMount,
      @NotBlank @DefaultValue("mw-admin-api/staged-credentials") String stagingPath,
      @NotNull @DefaultValue("2s") Duration connectTimeout,
      @NotNull @DefaultValue("5s") Duration readTimeout) {

    @Override
    public String toString() {
      return "Vault[enabled=" + enabled + ", uri=" + uri + ", token=****, kvMount=" + kvMount + ", stagingPath="
          + stagingPath + "]";
    }
  }

  /**
   * @param thresholdsDays an alert when a credential has at most this many days left, once per
   *     threshold and credential version (30, 7, 1)
   * @param opsEmail the operations mailbox the alert email goes to
   * @param locale the alert email's locale (bn|en)
   */
  public record Expiry(
      @NotEmpty @DefaultValue({"30", "7", "1"}) List<Integer> thresholdsDays,
      @NotBlank @Email @DefaultValue("ops@bits.example") String opsEmail,
      @NotBlank @DefaultValue("en") String locale) {}
}
