package mfs.mw.portal.api.security;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import mfs.mw.portal.api.dto.PortalUser;
import mfs.mw.portal.api.exception.ErrorCodes;
import mfs.mw.portal.api.exception.MwException;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Turns a validated realm {@code portals} token into the {@link PortalUser}. The org is the
 * {@code orgId} claim (user attribute {@code orgId}, mapped by the realm's {@code mw-portal}
 * client); a token without a well-formed org, or without a subject, is 403 {@code MW-AUTH-002}.
 */
@Component
public class PortalUserResolver {

  static final String ORG_CLAIM = "orgId";
  private static final Pattern ORG_ID = Pattern.compile("[A-Za-z0-9_-]{1,32}");

  public PortalUser resolve(Jwt jwt) {
    String org = jwt.getClaimAsString(ORG_CLAIM);
    if (org == null || !ORG_ID.matcher(org).matches()) {
      throw forbidden("Token carries no organisation");
    }
    String sub = jwt.getSubject();
    if (sub == null || sub.isBlank()) {
      throw forbidden("Token carries no subject");
    }
    String username = jwt.getClaimAsString("preferred_username");
    return new PortalUser(sub, username == null || username.isBlank() ? sub : username, org, realmRoles(jwt));
  }

  private static Set<String> realmRoles(Jwt jwt) {
    Map<String, Object> access = jwt.getClaimAsMap("realm_access");
    Set<String> out = new HashSet<>();
    if (access != null && access.get("roles") instanceof Collection<?> roles) {
      roles.forEach(r -> out.add(String.valueOf(r)));
    }
    return out;
  }

  public static MwException forbidden(String message) {
    return new MwException(ErrorCodes.AUTH_FORBIDDEN, message).withStatus(HttpStatus.FORBIDDEN);
  }
}
