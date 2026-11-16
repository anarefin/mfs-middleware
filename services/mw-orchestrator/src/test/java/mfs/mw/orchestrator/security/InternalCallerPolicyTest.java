package mfs.mw.orchestrator.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The caller rules of {@code /internal/v1/**} without a server: allowed, org-scoped, reveal and
 * wallet-scoped clients (ADR D25: mw-app-gateway may only search one wallet's history).
 */
class InternalCallerPolicyTest {

  static final InternalCallerPolicy POLICY =
      new InternalCallerPolicy(
          Set.of("mw-portal-api", "mw-admin-api", "mw-app-gateway"), Set.of("mw-portal-api"), Set.of("mw-admin-api"),
          Set.of("mw-app-gateway"));

  static Jwt token(String azp) {
    return Jwt.withTokenValue("t").header("alg", "none").claim("azp", azp).build();
  }

  static MockHttpServletRequest get(String path, Map<String, String> params) {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
    params.forEach(req::setParameter);
    return req;
  }

  static boolean allows(String client, MockHttpServletRequest req) {
    return POLICY.allows(token(client), req);
  }

  static final String SEARCH = "/internal/v1/transactions";
  static final String DETAIL = SEARCH + "/01933b8e-7c1a-7b2e-9f3d-2a4c5e6f7a8b";

  @Test
  void theWalletClientMaySearchOneWalletWithTheAllowListedFilters() {
    assertThat(allows("mw-app-gateway", get(SEARCH, Map.of("walletId", "01711000001")))).isTrue();
    assertThat(allows("mw-app-gateway", get(SEARCH, Map.of(
        "walletId", "01711000001", "direction", "IN", "txnType", "P2P", "status", "COMPLETED",
        "from", "2026-11-01T00:00:00Z", "to", "2026-11-02T00:00:00Z", "limit", "21", "offset", "0"))))
        .isTrue();
  }

  @Test
  void theWalletClientNeedsAWalletId() {
    assertThat(allows("mw-app-gateway", get(SEARCH, Map.of()))).isFalse();
    assertThat(allows("mw-app-gateway", get(SEARCH, Map.of("walletId", " ")))).isFalse();
  }

  @Test
  void theWalletClientMayNotUseAnyOtherFilter() {
    for (String param : new String[] {"msisdn", "orgId", "txnId", "partnerRef", "parentTxnId", "reveal", "x"}) {
      assertThat(allows("mw-app-gateway", get(SEARCH, Map.of("walletId", "01711000001", param, "1"))))
          .as(param)
          .isFalse();
    }
  }

  @Test
  void theWalletClientMayNotReadADetailOrPost() {
    assertThat(allows("mw-app-gateway", get(DETAIL, Map.of("walletId", "01711000001")))).isFalse();
    MockHttpServletRequest post = new MockHttpServletRequest("POST", SEARCH);
    post.setParameter("walletId", "01711000001");
    assertThat(allows("mw-app-gateway", post)).isFalse();
    assertThat(allows("mw-app-gateway", get(SEARCH + "/", Map.of("walletId", "01711000001")))).isFalse();
  }

  @Test
  void theOtherClientsKeepTheirRules() {
    assertThat(allows("mw-admin-api", get(SEARCH, Map.of()))).isTrue();
    assertThat(allows("mw-admin-api", get(DETAIL, Map.of("reveal", "true")))).isTrue();
    // an operator may filter by wallet too
    assertThat(allows("mw-admin-api", get(SEARCH, Map.of("walletId", "01711000001", "msisdn", "01711000002"))))
        .isTrue();
    assertThat(allows("mw-portal-api", get(SEARCH, Map.of("orgId", "ORG001")))).isTrue();
    assertThat(allows("mw-portal-api", get(SEARCH, Map.of("walletId", "01711000001")))).isFalse();
    assertThat(allows("mw-portal-api", get(DETAIL, Map.of("orgId", "ORG001", "reveal", "true")))).isFalse();
    assertThat(allows("partner-sim", get(SEARCH, Map.of("walletId", "01711000001")))).isFalse();
  }
}
