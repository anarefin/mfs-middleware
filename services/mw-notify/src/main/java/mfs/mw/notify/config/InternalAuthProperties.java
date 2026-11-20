package mfs.mw.notify.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code mw.internal-auth}: who may call {@code /internal/v1/**} (spec 18.2). A Keycloak
 * client-credentials JWT of realm {@code services}.
 */
@Validated
@ConfigurationProperties("mw.internal-auth")
public record InternalAuthProperties(
    @NotBlank String jwkSetUri,
    @NotBlank String issuer,
    @NotBlank String audience,
    @NotEmpty Set<String> allowedClients,
    Set<String> orgScopedClients) {

  public InternalAuthProperties {
    allowedClients = Set.copyOf(allowedClients == null ? Set.of() : allowedClients);
    orgScopedClients = Set.copyOf(orgScopedClients == null ? Set.of() : orgScopedClients);
  }
}
