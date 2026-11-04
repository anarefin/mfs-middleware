package mfs.mw.app.gateway.security;

import java.util.Collection;
import java.util.Map;
import mfs.mw.app.gateway.dto.AppUser;
import mfs.mw.app.gateway.exception.ErrorCodes;
import mfs.mw.app.gateway.exception.MwException;
import mfs.mw.app.gateway.util.Msisdn;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Turns a validated Keycloak token into the {@link AppUser}: the wallet is the {@code msisdn}
 * claim (user attribute {@code msisdn}, mapped by the realm's {@code mw-app} client), the channel
 * follows the realm role: {@code customer} is the customer app, {@code agent} the agent app
 * (spec WP20; which role may initiate which {@code txnType} is checked at submit). Other roles
 * (merchant) are not served yet.
 */
@Component
public class AppUserResolver {

  static final String MSISDN_CLAIM = "msisdn";

  public AppUser resolve(Jwt jwt) {
    String msisdn = Msisdn.canonical(jwt.getClaimAsString(MSISDN_CLAIM));
    if (msisdn == null) {
      throw forbidden("Token carries no wallet");
    }
    Collection<?> roles = realmRoles(jwt);
    if (roles.contains("customer")) {
      return new AppUser(msisdn, "CUSTOMER_APP", "CUSTOMER");
    }
    if (roles.contains("agent")) {
      return new AppUser(msisdn, "AGENT_APP", "AGENT");
    }
    throw forbidden("This app role cannot use this operation");
  }

  private static Collection<?> realmRoles(Jwt jwt) {
    Map<String, Object> access = jwt.getClaimAsMap("realm_access");
    if (access != null && access.get("roles") instanceof Collection<?> roles) {
      return roles;
    }
    return java.util.List.of();
  }

  private static MwException forbidden(String message) {
    return new MwException(ErrorCodes.AUTH_FORBIDDEN, message).withStatus(HttpStatus.FORBIDDEN);
  }
}
