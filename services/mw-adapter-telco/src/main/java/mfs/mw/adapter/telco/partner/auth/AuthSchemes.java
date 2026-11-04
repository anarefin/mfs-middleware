package mfs.mw.adapter.telco.partner.auth;

import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import mfs.mw.adapter.telco.partner.AuthScheme;
import mfs.mw.adapter.telco.partner.CredentialResolver;
import mfs.mw.adapter.telco.partner.config.PartnerConfig;

/** The auth schemes this adapter ships, built from a partner's {@code auth} config. */
public final class AuthSchemes {

  public static final Set<String> NAMES = Set.of("none", "api-key", "hmac-sha256");

  private AuthSchemes() {}

  public static AuthScheme create(PartnerConfig.Auth auth, CredentialResolver credentials, Clock clock) {
    return switch (auth.scheme()) {
      case "none" -> NoAuthScheme.INSTANCE;
      case "api-key" ->
          new ApiKeyAuthScheme(auth.params().getOrDefault("header", ApiKeyAuthScheme.DEFAULT_HEADER), credentials);
      case "hmac-sha256" ->
          new HmacSha256AuthScheme(
              credentials,
              clock,
              auth.params().getOrDefault("signature-header", HmacSha256AuthScheme.SIGNATURE_HEADER),
              auth.params().getOrDefault("timestamp-header", HmacSha256AuthScheme.TIMESTAMP_HEADER),
              auth.params().get("key-id"),
              Duration.ofSeconds(Long.parseLong(auth.params().getOrDefault("max-skew-seconds", "300"))));
      default -> throw new IllegalArgumentException("unknown auth scheme " + auth.scheme());
    };
  }
}
