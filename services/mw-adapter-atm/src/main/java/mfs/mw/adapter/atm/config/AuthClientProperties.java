package mfs.mw.adapter.atm.config;

import java.time.Duration;
import mfs.mw.adapter.atm.util.Sensitive;
import mfs.mw.adapter.atm.util.SensitiveType;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * mw-auth's internal API for the ATM token check (ticket 120b2; {@code POST
 * /internal/v1/atm-tokens/verifications}, the only path mw-auth lets this client reach): its base
 * URL, the client-credentials login of realm {@code services} (secret from Vault, never in a file)
 * and mTLS through the Spring SSL bundle {@code ssl-bundle} when set (mw-auth's internal port wants
 * a client certificate whose CN is this client).
 */
@ConfigurationProperties("mw.atm.auth")
public record AuthClientProperties(
    String baseUrl,
    String tokenUri,
    String clientId,
    @Sensitive(SensitiveType.SECRET) String clientSecret,
    String sslBundle,
    Duration connectTimeout,
    Duration readTimeout) {

  public AuthClientProperties {
    connectTimeout = connectTimeout == null ? Duration.ofSeconds(1) : connectTimeout;
    readTimeout = readTimeout == null ? Duration.ofSeconds(2) : readTimeout;
  }

  @Override
  public String toString() {
    return "AuthClientProperties[baseUrl=" + baseUrl + ", clientId=" + clientId + ", sslBundle=" + sslBundle + "]";
  }
}
