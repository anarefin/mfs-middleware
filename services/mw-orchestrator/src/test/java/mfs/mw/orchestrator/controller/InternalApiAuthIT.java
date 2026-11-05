package mfs.mw.orchestrator.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import mfs.mw.orchestrator.TestInfrastructure;
import mfs.mw.orchestrator.security.InternalTokens;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/**
 * Who may call the internal query API (spec 18.2, M3 gate decision): a Keycloak client-credentials
 * JWT of realm {@code services} for audience {@code mw-orchestrator}, from client mw-portal-api
 * (always with an {@code orgId}) or mw-admin-api. Everything else is the 5.3 envelope: 401
 * MW-AUTH-001 without a valid token, 403 MW-AUTH-002 for a caller that may not ask.
 */
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {"mw.orchestrator.consumers.enabled=false", "mw.orchestrator.timer.enabled=false"})
@Import(TestInfrastructure.class)
class InternalApiAuthIT {

  static final String SEARCH = "/internal/v1/transactions";
  static final String DETAIL = "/internal/v1/transactions/01933b8e-7c1a-7b2e-9f3d-2a4c5e6f7a8b";

  @LocalServerPort int port;

  private HttpResponse<String> get(String pathAndQuery, String authorization) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + pathAndQuery));
    if (authorization != null) {
      b.header("Authorization", authorization);
    }
    return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static void assertEnvelope(HttpResponse<String> res, int status, String code) {
    assertThat(res.statusCode()).as(res.body()).isEqualTo(status);
    assertThat(res.body()).contains("\"code\":\"" + code + "\"").contains("\"correlationId\"");
  }

  @Test
  void noTokenIs401() throws Exception {
    assertEnvelope(get(SEARCH, null), 401, "MW-AUTH-001");
    assertEnvelope(get(DETAIL + "?orgId=ORG001", null), 401, "MW-AUTH-001");
  }

  @Test
  void badTokensAre401() throws Exception {
    assertEnvelope(get(SEARCH, "Bearer not-a-jwt"), 401, "MW-AUTH-001");
    assertEnvelope(get(SEARCH, "Bearer " + InternalTokens.expired("mw-admin-api")), 401, "MW-AUTH-001");
    assertEnvelope(get(SEARCH, "Bearer " + InternalTokens.foreignKey("mw-admin-api")), 401, "MW-AUTH-001");
    assertEnvelope(
        get(SEARCH, "Bearer " + InternalTokens.withIssuer("mw-admin-api", "http://keycloak.test/realms/partners")),
        401, "MW-AUTH-001");
    assertEnvelope(
        get(SEARCH, "Bearer " + InternalTokens.withAudience("mw-admin-api", "mw-audit-dw")), 401, "MW-AUTH-001");
  }

  @Test
  void wrongClientIs403() throws Exception {
    assertEnvelope(get(SEARCH, InternalTokens.bearer("partner-sim")), 403, "MW-AUTH-002");
    assertEnvelope(get(SEARCH + "?orgId=ORG001", InternalTokens.bearer("mw-app")), 403, "MW-AUTH-002");
  }

  @Test
  void portalWithoutOrgIdIs403() throws Exception {
    assertEnvelope(get(SEARCH, InternalTokens.bearer("mw-portal-api")), 403, "MW-AUTH-002");
    assertEnvelope(get(SEARCH + "?orgId=", InternalTokens.bearer("mw-portal-api")), 403, "MW-AUTH-002");
    assertEnvelope(get(DETAIL, InternalTokens.bearer("mw-portal-api")), 403, "MW-AUTH-002");
  }

  @Test
  void portalWithOrgIdAndAdminAreServed() throws Exception {
    assertThat(get(SEARCH + "?orgId=ORG001", InternalTokens.bearer("mw-portal-api")).statusCode()).isEqualTo(200);
    assertThat(get(SEARCH, InternalTokens.bearer("mw-admin-api")).statusCode()).isEqualTo(200);
    // authorised, then simply not found
    assertEnvelope(get(DETAIL + "?orgId=ORG001", InternalTokens.bearer("mw-portal-api")), 404, "MW-VAL-003");
  }

  @Test
  void probesStayOpen() throws Exception {
    assertThat(get("/actuator/health/liveness", null).statusCode()).isEqualTo(200);
  }
}
