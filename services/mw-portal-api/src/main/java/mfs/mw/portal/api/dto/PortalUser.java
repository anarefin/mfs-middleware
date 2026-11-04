package mfs.mw.portal.api.dto;

import java.util.Set;

/**
 * The authenticated portal user, from the Keycloak realm {@code portals} token: {@code sub} (the
 * identity maker-checker compares), {@code preferred_username} (shown as maker/checker id; staff
 * usernames are not masked, M2 gate decision), the org (claim {@code orgId}) and the realm roles.
 */
public record PortalUser(String subject, String username, String orgId, Set<String> roles) {

  public PortalUser {
    roles = Set.copyOf(roles);
  }

  public boolean has(String role) {
    return roles.contains(role);
  }
}
