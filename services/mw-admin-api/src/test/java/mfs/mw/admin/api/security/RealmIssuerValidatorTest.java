package mfs.mw.admin.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

/** The realm is pinned by the issuer path; the host is whatever URL the client used. */
class RealmIssuerValidatorTest {

  final RealmIssuerValidator validator = new RealmIssuerValidator("/realms/ops");

  static Jwt issuedBy(String iss) {
    return Jwt.withTokenValue("t")
        .header("alg", "RS256")
        .issuer(iss)
        .subject("s")
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(60))
        .build();
  }

  @Test
  void opsRealmOnAnyHostIsAccepted() {
    assertThat(validator.validate(issuedBy("http://keycloak:8080/realms/ops")).hasErrors()).isFalse();
    assertThat(validator.validate(issuedBy("https://localhost:8443/realms/ops/")).hasErrors()).isFalse();
  }

  @Test
  void otherRealmsAreRejected() {
    assertThat(validator.validate(issuedBy("http://keycloak:8080/realms/portals")).hasErrors()).isTrue();
    assertThat(validator.validate(issuedBy("http://keycloak:8080/realms/services")).hasErrors()).isTrue();
    assertThat(validator.validate(issuedBy("http://keycloak:8080/realms/ops-evil")).hasErrors()).isTrue();
  }
}
