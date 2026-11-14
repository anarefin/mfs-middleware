package mfs.mw.admin.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import mfs.mw.admin.api.dto.AdminRole;
import mfs.mw.admin.api.dto.Operator;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

/** auth_time and the fresh-MFA rule of the key screens (ticket 75). */
class OperatorResolverTest {

  static final Instant NOW = Instant.parse("2026-11-15T10:00:00Z");
  static final Duration FIVE = Duration.ofMinutes(5);

  static Jwt token(Object authTime) {
    Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "RS256").subject("sub-a").claim("preferred_username", "a")
        .claim("realm_access", Map.of("roles", List.of("security-admin")));
    if (authTime != null) {
      b.claim("auth_time", authTime);
    }
    return b.build();
  }

  @Test
  void readsAuthTimeInEpochSeconds() {
    Operator op = new OperatorResolver().resolve(token(NOW.getEpochSecond() - 60));
    assertThat(op.authTime()).isEqualTo(NOW.minusSeconds(60));
    assertThat(op.roles()).containsExactly(AdminRole.SECURITY_ADMIN);
    assertThat(new OperatorResolver().resolve(token(null)).authTime()).isNull();
    assertThat(new OperatorResolver().resolve(token("not a time")).authTime()).isNull();
  }

  @Test
  void mfaIsFreshForFiveMinutesOnly() {
    assertThat(op(NOW.minusSeconds(299)).mfaFresh(NOW, FIVE)).isTrue();
    assertThat(op(NOW.minusSeconds(300)).mfaFresh(NOW, FIVE)).isTrue();
    assertThat(op(NOW.minusSeconds(301)).mfaFresh(NOW, FIVE)).isFalse();
    assertThat(op(null).mfaFresh(NOW, FIVE)).isFalse();
    // a sign-in time far in the future is not trusted
    assertThat(op(NOW.plusSeconds(3600)).mfaFresh(NOW, FIVE)).isFalse();
  }

  static Operator op(Instant authTime) {
    return new Operator("s", "u", java.util.Set.of(AdminRole.SECURITY_ADMIN), authTime);
  }
}
