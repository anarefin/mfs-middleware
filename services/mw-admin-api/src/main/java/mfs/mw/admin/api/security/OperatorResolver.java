package mfs.mw.admin.api.security;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import mfs.mw.admin.api.dto.AdminRole;
import mfs.mw.admin.api.dto.Operator;
import mfs.mw.admin.api.exception.ForbiddenException;
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
      throw new ForbiddenException("Token carries no subject");
    }
    String username = jwt.getClaimAsString("preferred_username");
    return new Operator(
        sub, username == null || username.isBlank() ? sub : username, AdminRole.from(realmRoles(jwt)), authTime(jwt));
  }

  /** OIDC {@code auth_time} (epoch seconds; Keycloak puts it in access tokens), null when absent or unreadable. */
  static Instant authTime(Jwt jwt) {
    Object v = jwt.getClaims().get("auth_time");
    if (v instanceof Instant i) {
      return i;
    }
    if (v instanceof Number n) {
      return Instant.ofEpochSecond(n.longValue());
    }
    if (v instanceof String s && s.matches("\\d{1,12}")) {
      return Instant.ofEpochSecond(Long.parseLong(s));
    }
    return null;
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
}
