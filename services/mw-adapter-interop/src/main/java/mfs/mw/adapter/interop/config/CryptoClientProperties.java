package mfs.mw.adapter.interop.config;

import java.time.Duration;
import mfs.mw.adapter.interop.util.Sensitive;
import mfs.mw.adapter.interop.util.SensitiveType;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * mw-crypto as this adapter's XML-DSig signer (ticket 121b; mw-crypto ticket 65, purpose XMLDSIG):
 * its base URL, the client-credentials login of realm {@code services} (secret from Vault, never in a
 * file), our signing key ({@code keyId}, default {@code xmldsig}) and the NPSB switch's certificate
 * the inbound messages are verified under ({@code switch-certificate-pem}, from Vault). mTLS through
 * the Spring SSL bundle {@code ssl-bundle} when set (mw-crypto requires a client certificate,
 * ticket 66). Off unless {@code enabled}.
 */
@ConfigurationProperties("mw.interop.crypto")
public record CryptoClientProperties(
    boolean enabled,
    String baseUrl,
    String tokenUri,
    String clientId,
    @Sensitive(SensitiveType.SECRET) String clientSecret,
    String keyId,
    String switchCertificatePem,
    String sslBundle,
    Duration connectTimeout,
    Duration readTimeout) {

  public CryptoClientProperties {
    keyId = keyId == null || keyId.isBlank() ? "xmldsig" : keyId;
    connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
    readTimeout = readTimeout == null ? Duration.ofSeconds(3) : readTimeout;
  }

  @Override
  public String toString() {
    return "CryptoClientProperties[enabled=" + enabled + ", baseUrl=" + baseUrl + ", clientId=" + clientId
        + ", keyId=" + keyId + ", sslBundle=" + sslBundle + "]";
  }
}
