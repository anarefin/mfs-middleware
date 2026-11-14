package mfs.mw.app.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import mfs.mw.app.gateway.dto.AppUser;
import mfs.mw.app.gateway.exception.MwException;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * An mw-auth access token as the app user (ticket 69, spec 18.7): the wallet is the {@code msisdn}
 * claim, the app follows the {@code role} claim, and only a PIN session ({@code amr} contains {@code
 * pin}) may use the app API: the token of a first-PIN OTP login is for {@code POST /auth/v1/pin} only.
 */
class AppUserResolverTest {

  final AppUserResolver resolver = new AppUserResolver();

  static Jwt token(Map<String, Object> claims) {
    Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "RS256").issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(300)).subject("01711000001");
    claims.forEach(b::claim);
    return b.build();
  }

  static Jwt app(String role) {
    return token(Map.of("msisdn", "01711000001", "role", role, "amr", List.of("pin", "device")));
  }

  @Test
  void theRoleClaimPicksTheApp() {
    assertThat(resolver.resolve(app("customer"))).isEqualTo(new AppUser("01711000001", "CUSTOMER_APP", "CUSTOMER"));
    assertThat(resolver.resolve(app("agent"))).isEqualTo(new AppUser("01711000001", "AGENT_APP", "AGENT"));
    assertThat(resolver.resolve(app("merchant"))).isEqualTo(new AppUser("01711000001", "MERCHANT_APP", "MERCHANT"));
  }

  @Test
  void aRoleWithoutAnAppHereIsRefused() {
    for (String role : List.of("distributor", "dso", "ops")) {
      assertThatThrownBy(() -> resolver.resolve(app(role)))
          .isInstanceOfSatisfying(MwException.class, e -> assertThat(e.code()).isEqualTo("MW-AUTH-002"));
    }
  }

  @Test
  void aKeycloakRealmRoleIsNoLongerARole() {
    Jwt keycloak = token(Map.of("msisdn", "01711000001", "realm_access", Map.of("roles", List.of("customer")),
        "amr", List.of("pin", "device")));
    assertThatThrownBy(() -> resolver.resolve(keycloak)).isInstanceOf(MwException.class);
  }

  @Test
  void aTokenWithoutAPinSessionIsForTheFirstPinOnly() {
    Jwt otpOnly = token(Map.of("msisdn", "01711000001", "role", "customer", "amr", List.of("otp", "device")));
    assertThatThrownBy(() -> resolver.resolve(otpOnly))
        .isInstanceOfSatisfying(MwException.class, e -> assertThat(e.code()).isEqualTo("MW-AUTH-045"));
    Jwt noAmr = token(Map.of("msisdn", "01711000001", "role", "customer"));
    assertThatThrownBy(() -> resolver.resolve(noAmr))
        .isInstanceOfSatisfying(MwException.class, e -> assertThat(e.code()).isEqualTo("MW-AUTH-045"));
  }

  @Test
  void aTokenWithoutAWalletIsRefused() {
    Jwt none = token(Map.of("role", "customer", "amr", List.of("pin", "device")));
    assertThatThrownBy(() -> resolver.resolve(none))
        .isInstanceOfSatisfying(MwException.class, e -> assertThat(e.code()).isEqualTo("MW-AUTH-002"));
  }
}
