package mfs.mw.crypto.security;

import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The caller rules of {@code /internal/v1/**} (spec 18.2, 18.8; copied from mw-audit-dw, D14). A
 * token must name {@code mw-crypto} in {@code aud} ({@link #audience}); the calling client ({@code
 * azp}, Keycloak's client id of a client-credentials token) must be on the allow-list. Ticket 66
 * adds the mTLS rule (azp equals the client certificate's CN).
 */
public final class InternalCallerPolicy {

  private static final Logger log = LoggerFactory.getLogger(InternalCallerPolicy.class);

  private final Set<String> allowedClients;

  public InternalCallerPolicy(Set<String> allowedClients) {
    this.allowedClients = Set.copyOf(allowedClients);
  }

  /** Whether the authenticated client may call. */
  public boolean allows(Jwt jwt) {
    String client = jwt.getClaimAsString("azp");
    boolean allowed = client != null && allowedClients.contains(client);
    if (!allowed) {
      log.atWarn()
          .addKeyValue("event", "security.internal_call_denied")
          .addKeyValue("clientId", client == null ? "none" : client)
          .addKeyValue("reason", "CLIENT_NOT_ALLOWED")
          .log("Internal API call refused");
    }
    return allowed;
  }

  /** Token validator: {@code aud} must contain {@code audience}. */
  public static OAuth2TokenValidator<Jwt> audience(String audience) {
    return jwt -> {
      List<String> aud = jwt.getAudience();
      return aud != null && aud.contains(audience)
          ? OAuth2TokenValidatorResult.success()
          : OAuth2TokenValidatorResult.failure(
              new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, "token is not for this service", null));
    };
  }
}
