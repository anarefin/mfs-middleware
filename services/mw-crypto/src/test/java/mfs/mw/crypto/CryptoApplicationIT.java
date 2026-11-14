package mfs.mw.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

/**
 * The chassis starts and exposes the endpoints the Compose healthcheck and scraper use (20.1, 21.2),
 * on the management port (8081 in the image: the API port is mTLS only, D24).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestInfrastructure.class)
class CryptoApplicationIT {

  private final HttpClient http = HttpClient.newHttpClient();

  @LocalServerPort int port;
  @LocalManagementPort int managementPort;

  HttpResponse<String> get(String path) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + (path.startsWith("/actuator") ? managementPort : port) + path)).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void livenessAndReadinessAreUp() throws Exception {
    assertThat(get("/actuator/health/liveness").body()).contains("\"status\":\"UP\"");
    assertThat(get("/actuator/health/readiness").body()).contains("\"status\":\"UP\"");
  }

  @Test
  void prometheusEndpointServesMetrics() throws Exception {
    HttpResponse<String> res = get("/actuator/prometheus");
    assertThat(res.statusCode()).isEqualTo(200);
    assertThat(res.body()).contains("jvm_memory_used_bytes");
  }

  @Test
  void virtualThreadsAreOn(@org.springframework.beans.factory.annotation.Autowired Environment env) {
    assertThat(env.getProperty("spring.threads.virtual.enabled")).isEqualTo("true");
  }

  @Test
  void responsesCarrySecurityHeaders() throws Exception {
    HttpResponse<String> res = get("/internal/v1/keys");
    assertThat(res.statusCode()).isEqualTo(401);
    assertThat(res.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
    assertThat(res.headers().firstValue("X-Frame-Options")).hasValue("DENY");
  }
}
