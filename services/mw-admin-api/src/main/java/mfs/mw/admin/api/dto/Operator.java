package mfs.mw.admin.api.dto;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import mfs.mw.admin.api.security.AdminRole;

/**
 * The authenticated operator, from a Keycloak realm {@code ops} token: {@code sub} (the identity a
 * maker-checker comparison will use), {@code preferred_username} (the audit actor; staff usernames
 * are not masked, M2 gate decision) and the spec 15.4 roles it holds.
 */
public record Operator(String subject, String username, Set<AdminRole> roles) {

  public Operator {
    roles = Set.copyOf(roles);
  }

  /** Whether the operator holds any of {@code allowed}. */
  public boolean hasAny(Collection<AdminRole> allowed) {
    return allowed.stream().anyMatch(roles::contains);
  }

  /** The first of {@code allowed} (in declaration order) the operator holds. */
  public Optional<AdminRole> first(Collection<AdminRole> allowed) {
    return roles.stream().filter(allowed::contains).sorted().findFirst();
  }
}
