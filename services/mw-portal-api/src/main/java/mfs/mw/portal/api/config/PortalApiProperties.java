package mfs.mw.portal.api.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * {@code mw.portal-api.*}: bulk file limits (spec 9.4), the Keycloak realm whose tokens are
 * accepted and the realm roles of maker and checker (18.2).
 */
@Validated
@ConfigurationProperties("mw.portal-api")
public record PortalApiProperties(
    @Valid @NotNull Bulk bulk, @Valid @NotNull Jwt jwt, @Valid @NotNull Roles roles) {

  /**
   * @param maxRows most data rows in one file (MVP: 10,000; spec 9.4 says up to 100,000, TBC)
   * @param maxBytes largest file accepted (APISIX caps the route body a little above it)
   */
  public record Bulk(@Positive int maxRows, @NotNull DataSize maxBytes) {}

  /**
   * @param jwkSetUri the realm's signing keys, fetched lazily on the first token
   * @param realmPath required ending of the token's {@code iss} (the host depends on the URL used)
   */
  public record Jwt(@NotBlank String jwkSetUri, @NotBlank String realmPath) {}

  /** Realm roles: the maker uploads, the checker approves or rejects. */
  public record Roles(@NotBlank String maker, @NotBlank String checker) {}
}
