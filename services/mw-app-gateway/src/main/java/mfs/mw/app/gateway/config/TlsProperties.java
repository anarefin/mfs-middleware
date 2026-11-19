package mfs.mw.app.gateway.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code mw.tls}: this service's certificate from Vault PKI (spec 18.2, 18.4, D24;
 * {@link ServiceCertificateManager}).
 *
 * @param enabled issue a certificate at start and keep it renewed (needs Spring Cloud Vault)
 * @param pkiMount the Vault PKI mount that issues service certificates ({@code pki_int})
 * @param role the PKI role, named after the service (its policy may issue from this role only)
 * @param commonName the certificate CN: the service name, which callees compare with the JWT azp
 * @param altNames DNS SANs: the compose DNS name (= service name)
 * @param ttl requested life (the role caps it at 72 h); renewed at two thirds of the actual life
 * @param retryPeriod wait before another try when issuing a renewal failed
 * @param bundle the Spring Boot SSL bundle name ({@code server.ssl.bundle} of an mTLS server)
 */
@Validated
@ConfigurationProperties("mw.tls")
public record TlsProperties(
    boolean enabled,
    @NotBlank String pkiMount,
    @NotBlank String role,
    @NotBlank String commonName,
    List<String> altNames,
    @NotNull Duration ttl,
    @NotNull Duration retryPeriod,
    @NotBlank String bundle) {

  public TlsProperties {
    altNames = List.copyOf(altNames == null ? List.of() : altNames);
  }
}
