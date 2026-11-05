package mfs.mw.admin.api.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import mfs.mw.admin.api.dto.Operator;
import mfs.mw.admin.api.exception.ErrorCodes;
import mfs.mw.admin.api.exception.MwException;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Turns a validated realm {@code ops} token into the {@link Operator}: subject, username and the
 * spec 15.4 roles among its realm roles ({@code realm_access.roles}). A token without a subject is
 * 403 {@code MW-AUTH-002}.
 */
@Component
public class OperatorResolver {

  public Operator resolve(Jwt jwt) {
    String sub = jwt.getSubject();
    if (sub == null || sub.isBlank()) {
      throw forbidden("Token carries no subject");
    }
    String username = jwt.getClaimAsString("preferred_username");
    return new Operator(sub, username == null || username.isBlank() ? sub : username, AdminRole.from(realmRoles(jwt)));
  }

  /** The token's realm roles, as Keycloak puts them in {@code realm_access.roles}. */
  public static List<String> realmRoles(Jwt jwt) {
    Map<String, Object> access = jwt.getClaimAsMap("realm_access");
    List<String> out = new ArrayList<>();
    if (access != null && access.get("roles") instanceof Collection<?> roles) {
      roles.forEach(r -> out.add(String.valueOf(r)));
    }
    return out;
  }

  public static MwException forbidden(String message) {
    return new MwException(ErrorCodes.AUTH_FORBIDDEN, message).withStatus(HttpStatus.FORBIDDEN);
  }
}
