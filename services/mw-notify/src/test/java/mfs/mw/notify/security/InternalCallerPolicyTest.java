package mfs.mw.notify.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.Jwt;

/** The inbox is mw-app-gateway only; the notification log stays mw-admin-api (ticket 107a, 111). */
class InternalCallerPolicyTest {

  private final InternalCallerPolicy policy =
      new InternalCallerPolicy(Set.of("mw-admin-api"), Set.of(), Set.of("mw-app-gateway"));

  @Test
  void theInboxAndTheLogHaveDifferentCallers() {
    MockHttpServletRequest inbox = new MockHttpServletRequest("GET", "/internal/v1/inbox");
    MockHttpServletRequest read = new MockHttpServletRequest("POST", "/internal/v1/inbox/m-1/read");
    MockHttpServletRequest log = new MockHttpServletRequest("GET", "/internal/v1/notifications");

    assertThat(policy.allows(jwt("mw-app-gateway"), inbox)).isTrue();
    assertThat(policy.allows(jwt("mw-app-gateway"), read)).isTrue();
    assertThat(policy.allows(jwt("mw-admin-api"), inbox)).isFalse();
    assertThat(policy.allows(jwt("mw-admin-api"), read)).isFalse();
    assertThat(policy.allows(jwt("mw-admin-api"), log)).isTrue();
    assertThat(policy.allows(jwt("mw-app-gateway"), log)).isFalse();
  }

  private static Jwt jwt(String client) {
    return new Jwt("token", Instant.EPOCH, Instant.EPOCH.plusSeconds(60), Map.of("alg", "none"), Map.of("azp", client));
  }
}
