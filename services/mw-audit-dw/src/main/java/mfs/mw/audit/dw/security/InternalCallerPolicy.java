package mfs.mw.audit.dw.security;

import jakarta.servlet.http.HttpServletRequest;
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
 * The caller rules of {@code /internal/v1/**} (spec 18.2, M3 gate decision). A token must name this
 * service in {@code aud} ({@link #audience}); the calling client ({@code azp}, Keycloak's client id
 * of a client-credentials token) must be allowed, and an org-scoped client (mw-portal-api) must
 * always send a non-blank {@code orgId}, so it can only ever read its own org's data.
 */
public final class InternalCallerPolicy {

  static final String ORG_PARAM = "orgId";
  private static final Logger log = LoggerFactory.getLogger(InternalCallerPolicy.class);

  private final Set<String> allowedClients;
  private final Set<String> orgScopedClients;

  public InternalCallerPolicy(Set<String> allowedClients, Set<String> orgScopedClients) {
    this.allowedClients = Set.copyOf(allowedClients);
    this.orgScopedClients = Set.copyOf(orgScopedClients);
  }

  /** Whether the authenticated client may make this request. */
  public boolean allows(Jwt jwt, HttpServletRequest request) {
    String client = jwt.getClaimAsString("azp");
    boolean allowed;
    String reason;
    if (client == null || !allowedClients.contains(client)) {
      allowed = false;
      reason = "CLIENT_NOT_ALLOWED";
    } else if (orgScopedClients.contains(client) && isBlank(request.getParameter(ORG_PARAM))) {
      allowed = false;
      reason = "ORG_REQUIRED";
    } else {
      allowed = true;
      reason = null;
    }
    if (!allowed) {
      log.atWarn()
          .addKeyValue("event", "security.internal_call_denied")
          .addKeyValue("clientId", client == null ? "none" : client)
          .addKeyValue("reason", reason)
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

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }
}
