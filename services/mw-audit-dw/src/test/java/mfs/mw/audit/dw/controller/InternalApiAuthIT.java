package mfs.mw.audit.dw.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import mfs.mw.audit.dw.TestInfrastructure;
import mfs.mw.audit.dw.security.InternalTokens;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/**
 * Who may call the internal audit search (spec 18.2, M3 gate decision): a Keycloak
 * client-credentials JWT of realm {@code services} for audience {@code mw-audit-dw}, from
 * mw-admin-api only (the search has no org filter, so mw-portal-api is refused even with an
 * {@code orgId}). 401 MW-AUTH-001 without a valid token, 403 MW-AUTH-002 for any other client.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "mw.audit-dw.consumers.enabled=false")
@Import(TestInfrastructure.class)
class InternalApiAuthIT {

  static final String SEARCH = "/internal/v1/audit/events";

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
  void noOrBadTokenIs401() throws Exception {
    assertEnvelope(get(SEARCH, null), 401, "MW-AUTH-001");
    assertEnvelope(get(SEARCH, "Bearer " + InternalTokens.expired("mw-admin-api")), 401, "MW-AUTH-001");
    assertEnvelope(get(SEARCH, "Bearer " + InternalTokens.foreignKey("mw-admin-api")), 401, "MW-AUTH-001");
    assertEnvelope(
        get(SEARCH, "Bearer " + InternalTokens.withIssuer("mw-admin-api", "http://keycloak.test/realms/portals")),
        401, "MW-AUTH-001");
    assertEnvelope(
        get(SEARCH, "Bearer " + InternalTokens.withAudience("mw-admin-api", "mw-orchestrator")), 401, "MW-AUTH-001");
  }

  @Test
  void wrongClientIs403() throws Exception {
    assertEnvelope(get(SEARCH, InternalTokens.bearer("partner-sim")), 403, "MW-AUTH-002");
    assertEnvelope(get(SEARCH, InternalTokens.bearer("mw-portal-api")), 403, "MW-AUTH-002");
    assertEnvelope(get(SEARCH + "?orgId=ORG001", InternalTokens.bearer("mw-portal-api")), 403, "MW-AUTH-002");
  }

  @Test
  void adminIsServed() throws Exception {
    assertThat(get(SEARCH, InternalTokens.bearer("mw-admin-api")).statusCode()).isEqualTo(200);
  }

  @Test
  void probesStayOpen() throws Exception {
    assertThat(get("/actuator/health/liveness", null).statusCode()).isEqualTo(200);
  }
}
