package mfs.mw.app.gateway.security;

import java.util.Collection;
import java.util.Map;
import java.util.regex.Pattern;
import mfs.mw.app.gateway.dto.AppUser;
import mfs.mw.app.gateway.exception.ErrorCodes;
import mfs.mw.app.gateway.exception.MwException;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Turns a validated Keycloak token into the {@link AppUser}: the wallet is the {@code msisdn}
 * claim (user attribute {@code msisdn}, mapped by the realm's {@code mw-app} client), the channel
 * follows the realm role. Only the {@code customer} role is served so far; agent and merchant
 * apps come with spec WP20.
 */
@Component
public class AppUserResolver {

  static final String MSISDN_CLAIM = "msisdn";
  private static final Pattern MSISDN = Pattern.compile("01[3-9][0-9]{8}");

  public AppUser resolve(Jwt jwt) {
    String msisdn = jwt.getClaimAsString(MSISDN_CLAIM);
    if (msisdn == null || !MSISDN.matcher(msisdn).matches()) {
      throw forbidden("Token carries no wallet");
    }
    if (!realmRoles(jwt).contains("customer")) {
      throw forbidden("This app role cannot use this operation");
    }
    return new AppUser(msisdn, "CUSTOMER_APP", "CUSTOMER");
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
