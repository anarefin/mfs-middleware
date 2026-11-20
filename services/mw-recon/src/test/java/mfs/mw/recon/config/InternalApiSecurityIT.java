package mfs.mw.recon.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import mfs.mw.recon.TestInfrastructure;
import mfs.mw.recon.security.InternalTokens;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.json.JsonMapper;

/**
 * Ticket 127b security review: {@code /internal/**} needs a realm services token with mw-recon in {@code aud} from
 * mw-admin-api; without one nothing is read or recorded (the operator header alone is never trusted).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestInfrastructure.class)
class InternalApiSecurityIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final HttpClient http = HttpClient.newHttpClient();

  @LocalServerPort
  int port;

  private HttpResponse<String> get(String path, String token) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET();
    if (token != null) {
      b.header("Authorization", "Bearer " + token);
    }
    return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> action(String token) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(
            URI.create("http://127.0.0.1:" + port + "/internal/v1/recon/exceptions/" + UUID.randomUUID() + "/actions"))
        .header("Content-Type", "application/json")
        .header("X-Mw-Operator-Id", "someone")
        .POST(HttpRequest.BodyPublishers.ofString(
            "{\"action\":\"FORCE_COMMIT\",\"changeRequestId\":\"" + UUID.randomUUID() + "\"}"));
    if (token != null) {
      b.header("Authorization", "Bearer " + token);
    }
    return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static String code(HttpResponse<String> res) {
    return JSON.readTree(res.body()).path("code").asString();
  }

  @Test
  void noTokenOrABadTokenIs401() throws Exception {
    HttpResponse<String> none = get("/internal/v1/recon/exceptions", null);
    assertThat(none.statusCode()).isEqualTo(401);
    assertThat(code(none)).isEqualTo("MW-AUTH-001");
    assertThat(action(null).statusCode()).isEqualTo(401);
    assertThat(get("/internal/v1/recon/exceptions", InternalTokens.withAudience("mw-admin-api", "mw-core-mock"))
        .statusCode()).isEqualTo(401);
    assertThat(get("/internal/v1/recon/exceptions", InternalTokens.expired("mw-admin-api")).statusCode()).isEqualTo(401);
    assertThat(get("/internal/v1/recon/exceptions", InternalTokens.foreignKey("mw-admin-api")).statusCode())
        .isEqualTo(401);
  }

  @Test
  void anotherClientIs403() throws Exception {
    HttpResponse<String> other = action(InternalTokens.forClient("mw-orchestrator"));
    assertThat(other.statusCode()).isEqualTo(403);
    assertThat(code(other)).isEqualTo("MW-AUTH-002");
  }

  @Test
  void mwAdminApiIsServedAndTheProbesStayOpen() throws Exception {
    assertThat(get("/internal/v1/recon/exceptions", InternalTokens.forClient("mw-admin-api")).statusCode())
        .isEqualTo(200);
    // authenticated: the action reaches the service (an unknown exception is 404, not 401/403)
    assertThat(action(InternalTokens.forClient("mw-admin-api")).statusCode()).isEqualTo(404);
    assertThat(get("/actuator/health/liveness", null).statusCode()).isEqualTo(200);
  }
}
