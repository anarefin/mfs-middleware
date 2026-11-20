package mfs.mw.notify.security;

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
 * Caller rules of {@code /internal/v1/**} (spec 18.2): audience and an allowed client ({@code azp}).
 * The customer inbox ({@code /internal/v1/inbox}) is {@code mw-app-gateway} only. The notification
 * log stays on {@code allowedClients} (ticket 111, {@code mw-admin-api}).
 */
public final class InternalCallerPolicy {

  private static final Logger log = LoggerFactory.getLogger(InternalCallerPolicy.class);

  private final Set<String> allowedClients;
  private final Set<String> orgScopedClients;
  private final Set<String> inboxClients;

  public InternalCallerPolicy(Set<String> allowedClients, Set<String> orgScopedClients, Set<String> inboxClients) {
    this.allowedClients = Set.copyOf(allowedClients);
    this.orgScopedClients = Set.copyOf(orgScopedClients);
    this.inboxClients = Set.copyOf(inboxClients);
  }

  public boolean allows(Jwt jwt, HttpServletRequest request) {
    String client = jwt.getClaimAsString("azp");
    boolean inbox = request.getRequestURI() != null && request.getRequestURI().startsWith("/internal/v1/inbox");
    boolean allowed = client != null && (inbox ? inboxClients : allowedClients).contains(client)
        && (inbox || !(orgScopedClients.contains(client) && isBlank(request.getParameter("orgId"))));
    if (!allowed) {
      log.atWarn().addKeyValue("event", "security.internal_call_denied")
          .addKeyValue("clientId", client == null ? "none" : client)
          .log("Internal API call refused");
    }
    return allowed;
  }

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
