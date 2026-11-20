package mfs.mw.auth.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The per-path caller rules (ticket 120b2): a client limited to some paths reaches only those, and a
 * path reserved for some clients is refused to every other allowed client.
 */
class InternalCallerPolicyTest {

  final InternalCallerPolicy policy = new InternalCallerPolicy(
      Set.of("mw-app-gateway", "mw-ussd", "mw-adapter-atm"),
      Map.of("mw-adapter-atm", List.of("/internal/v1/atm-tokens/verifications")),
      Map.of("mw-app-gateway", List.of("/internal/v1/devices/**", "/internal/v1/sessions/**")),
      false, r -> false);

  static Jwt jwt(String azp) {
    return Jwt.withTokenValue("t").header("alg", "RS256").claim("azp", azp)
        .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
  }

  static MockHttpServletRequest at(String method, String path) {
    MockHttpServletRequest r = new MockHttpServletRequest(method, path);
    r.setRequestURI(path);
    return r;
  }

  @Test
  void theAtmAdapterReachesOnlyTheTokenCheck() {
    assertThat(policy.allows(jwt("mw-adapter-atm"), at("POST", "/internal/v1/atm-tokens/verifications"))).isTrue();
    for (String p : List.of("/internal/v1/atm-tokens", "/internal/v1/txn-authorisations",
        "/internal/v1/devices/dev-1", "/internal/v1/sessions/01711000001/dev-1", "/internal/v1/otps",
        "/internal/v1/atm-tokens/verifications/", "/internal/v1/atm-tokens/verifications/x")) {
      assertThat(policy.allows(jwt("mw-adapter-atm"), at("POST", p))).as(p).isFalse();
    }
  }

  @Test
  void theDeviceAndSessionLookupsAreTheGatewaysOnly() {
    assertThat(policy.allows(jwt("mw-app-gateway"), at("GET", "/internal/v1/devices/dev-1"))).isTrue();
    assertThat(policy.allows(jwt("mw-app-gateway"), at("GET", "/internal/v1/sessions/0171/dev-1"))).isTrue();
    assertThat(policy.allows(jwt("mw-ussd"), at("GET", "/internal/v1/devices/dev-1"))).isFalse();
    assertThat(policy.allows(jwt("mw-ussd"), at("GET", "/internal/v1/sessions/0171/dev-1"))).isFalse();
    assertThat(policy.allows(jwt("mw-ussd"), at("POST", "/internal/v1/txn-authorisations"))).isTrue();
  }

  @Test
  void aClientNotOnTheListIsRefusedEverywhere() {
    assertThat(policy.allows(jwt("mw-other"), at("POST", "/internal/v1/txn-authorisations"))).isFalse();
    assertThat(policy.allows(jwt(null), at("POST", "/internal/v1/txn-authorisations"))).isFalse();
  }
}
