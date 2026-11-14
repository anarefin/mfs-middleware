package mfs.mw.admin.api.dto;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;

/**
 * The authenticated operator, from a Keycloak realm {@code ops} token: {@code sub} (the identity a
 * maker-checker comparison will use), {@code preferred_username} (the audit actor; staff usernames
 * are not masked, M2 gate decision), the spec 15.4 roles it holds and {@code auth_time} (when the
 * operator last signed in with password and TOTP; null when the token has none), which the key
 * screens check for a fresh MFA (ticket 75).
 */
public record Operator(String subject, String username, Set<AdminRole> roles, Instant authTime) {

  public Operator {
    roles = Set.copyOf(roles);
  }

  public Operator(String subject, String username, Set<AdminRole> roles) {
    this(subject, username, roles, null);
  }

  /** Whether the operator signed in (MFA) at most {@code maxAge} before {@code now}. */
  public boolean mfaFresh(Instant now, Duration maxAge) {
    return authTime != null && !authTime.isAfter(now.plusSeconds(60)) && !authTime.plus(maxAge).isBefore(now);
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
