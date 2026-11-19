package mfs.mw.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import mfs.mw.auth.PkiTestAuthority;
import mfs.mw.auth.TestInfrastructure;
import mfs.mw.auth.config.PublicConnectorConfig;
import mfs.mw.auth.config.ServiceCertificateManager;
import mfs.mw.auth.security.InternalTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.vault.core.VaultOperations;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * mw-auth's internal API on its own port with mTLS (ticket 67, spec 18.7, D24), the server setup of
 * mw-crypto: {@code server.ssl.client-auth: need} on this service's Vault certificate (SAN
 * localhost here), plus the JWT allow-list (mw-app-gateway, mw-ussd, mw-checkout, mw-admin-api)
 * with {@code azp} equal to the client certificate's CN. The public port never serves {@code
 * /internal/**}; actuator stays on the plain management port. A test-only {@code GET
 * /internal/v1/ping} stands in for the operations of tickets 70-71.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"mw.tls.enabled=true", "mw.tls.alt-names=mw-auth,localhost"})
@Import({TestInfrastructure.class, InternalApiMtlsIT.Pki.class, InternalApiMtlsIT.Ping.class})
class InternalApiMtlsIT {

  static final JsonMapper JSON = JsonMapper.builder().build();

  /** Stands in for Spring Cloud Vault's VaultTemplate (AppRole on the stack). */
  @TestConfiguration(proxyBeanMethods = false)
  static class Pki {
    @Bean
    VaultOperations vaultOperations() {
      PkiTestAuthority.role("mw-auth", true);
      return PkiTestAuthority.vault();
    }
  }

  @RestController
  static class Ping {
    @GetMapping("/internal/v1/ping")
    Map<String, String> ping() {
      return Map.of("status", "ok");
    }
  }

  private final int internalPort;
  private final int managementPort;
  private final PublicConnectorConfig.PublicConnector publicConnector;
  private final ServiceCertificateManager certificates;

  @Autowired
  InternalApiMtlsIT(
      @Value("${local.server.port}") int internalPort,
      @Value("${local.management.port}") int managementPort,
      PublicConnectorConfig.PublicConnector publicConnector,
      ServiceCertificateManager certificates) {
    this.internalPort = internalPort;
    this.managementPort = managementPort;
    this.publicConnector = publicConnector;
    this.certificates = certificates;
  }

  /** A caller presenting a certificate of {@code certRole} (null = none), trusting the intermediate. */
  private SSLContext caller(String certRole) throws Exception {
    if (certRole != null) {
      return PkiTestAuthority.localhostServerContext(certRole);
    }
    TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    tmf.init(certificates.bundle().getStores().getTrustStore());
    SSLContext ctx = SSLContext.getInstance("TLS");
    ctx.init(null, tmf.getTrustManagers(), null);
    return ctx;
  }

  private HttpResponse<String> get(String url, SSLContext ssl, String token) throws Exception {
    HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5));
    if (ssl != null) {
      b.sslContext(ssl);
    }
    HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET();
    if (token != null) {
      req.header("Authorization", "Bearer " + token);
    }
    try (HttpClient http = b.build()) {
      return http.send(req.build(), HttpResponse.BodyHandlers.ofString());
    }
  }

  private HttpResponse<String> internal(String certRole, String token) throws Exception {
    return get("https://localhost:" + internalPort + "/internal/v1/ping", caller(certRole), token);
  }

  private static String code(HttpResponse<String> r) {
    JsonNode body = JSON.readTree(r.body());
    return body.path("code").asString();
  }

  @Test
  void anAllowedChannelWithItsOwnCertificateAndTokenIsServed() throws Exception {
    for (String client : new String[] {"mw-app-gateway", "mw-ussd", "mw-checkout", "mw-admin-api"}) {
      HttpResponse<String> r = internal(client, InternalTokens.forClient(client));
      assertThat(r.statusCode()).as(client).isEqualTo(200);
      assertThat(r.body()).contains("ok");
    }
  }

  @Test
  void aTokenOfAnotherClientThanTheCertificateIsRefused() throws Exception {
    HttpResponse<String> r = internal("mw-ussd", InternalTokens.forClient("mw-app-gateway"));
    assertThat(r.statusCode()).isEqualTo(403);
    assertThat(code(r)).isEqualTo("MW-AUTH-002");
  }

  @Test
  void aClientOffTheAllowListIsRefused() throws Exception {
    HttpResponse<String> r = internal("mw-notify", InternalTokens.forClient("mw-notify"));
    assertThat(r.statusCode()).isEqualTo(403);
    assertThat(code(r)).isEqualTo("MW-AUTH-002");
  }

  @Test
  void noOrABadTokenIs401() throws Exception {
    assertThat(code(internal("mw-app-gateway", null))).isEqualTo("MW-AUTH-001");
    assertThat(internal("mw-app-gateway", InternalTokens.expired("mw-app-gateway")).statusCode()).isEqualTo(401);
    assertThat(internal("mw-app-gateway", InternalTokens.withAudience("mw-app-gateway", "mw-crypto")).statusCode())
        .isEqualTo(401);
    assertThat(internal("mw-app-gateway", InternalTokens.foreignKey("mw-app-gateway")).statusCode()).isEqualTo(401);
  }

  @Test
  void withoutAClientCertificateTheHandshakeFails() {
    assertThatThrownBy(() -> internal(null, InternalTokens.forClient("mw-app-gateway"))).isInstanceOf(IOException.class);
  }

  @Test
  void thePublicPortNeverServesTheInternalApi() throws Exception {
    assertThat(publicConnector.localPort()).isPositive().isNotEqualTo(internalPort);
    HttpResponse<String> r =
        get("http://localhost:" + publicConnector.localPort() + "/internal/v1/ping", null,
            InternalTokens.forClient("mw-app-gateway"));
    assertThat(r.statusCode()).isEqualTo(403);
    assertThat(code(r)).isEqualTo("MW-AUTH-002");
  }

  @Test
  void healthIsOnThePlainManagementPort() throws Exception {
    HttpResponse<String> r = get("http://localhost:" + managementPort + "/actuator/health/readiness", null, null);
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(r.body()).contains("UP");
  }
}
