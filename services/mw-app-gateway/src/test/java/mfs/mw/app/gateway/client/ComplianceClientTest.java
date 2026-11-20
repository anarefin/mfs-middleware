package mfs.mw.app.gateway.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import mfs.mw.app.gateway.client.ComplianceClient.Subject;
import mfs.mw.app.gateway.config.AuthClientProperties;
import mfs.mw.app.gateway.config.ComplianceClientProperties;
import mfs.mw.app.gateway.config.ServiceCertificateManager;
import mfs.mw.app.gateway.exception.MwException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link ComplianceClient} against a WireMock mw-compliance (ticket 124b; plain HTTP here, the mTLS client context
 * is the one {@code AuthClientIT} proves): the service token and the subject headers on every call, mw-compliance's
 * envelope passing through, the 401 token refresh, and fail closed with {@code MW-SYS-017}.
 */
class ComplianceClientTest {

  static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();
  static final String APPS = "/internal/v1/kyc/applications";
  static final String APP = APPS + "/11111111-1111-4111-8111-111111111111";
  static final Subject AGENT = new Subject("AGENT", "01811000017");
  static final String ENVELOPE = "{\"code\":\"%s\",\"message\":\"m\",\"correlationId\":\"c\",\"retryable\":%s,\"details\":[]}";

  static WireMockServer compliance;
  static WireMockServer keycloak;
  ComplianceClient client;

  @BeforeAll
  static void start() {
    compliance = new WireMockServer(options().dynamicPort());
    compliance.start();
    keycloak = new WireMockServer(options().dynamicPort());
    keycloak.start();
  }

  @AfterAll
  static void stop() {
    compliance.stop();
    keycloak.stop();
  }

  @BeforeEach
  void stubs() {
    compliance.resetAll();
    keycloak.resetAll();
    keycloak.stubFor(post(urlEqualTo("/token")).willReturn(okJson("{\"access_token\":\"tok-1\",\"expires_in\":300}")));
    AuthClientProperties auth = new AuthClientProperties(URI.create("https://mw-auth:8443"),
        URI.create("http://127.0.0.1:" + keycloak.port() + "/token"), "mw-app-gateway", "fake-it-secret",
        Duration.ofMillis(300), Duration.ofMillis(500), Duration.ofMinutes(10));
    ComplianceClientProperties props = new ComplianceClientProperties(
        URI.create("http://127.0.0.1:" + compliance.port()), Duration.ofMillis(300), Duration.ofMillis(500),
        2 * 1024 * 1024, Set.of("image/jpeg", "image/png"));
    client = new ComplianceClient(props, new ServiceTokenClient(auth, JSON, Clock.systemUTC()),
        CircuitBreakerRegistry.ofDefaults(),
        new StaticListableBeanFactory().getBeanProvider(ServiceCertificateManager.class), JSON);
  }

  @Test
  void everyCallCarriesTheServiceTokenAndTheSubject() {
    compliance.stubFor(post(urlEqualTo(APPS)).willReturn(okJson("{\"applicationId\":\"a\"}").withStatus(201)));
    compliance.stubFor(get(urlEqualTo(APP)).willReturn(okJson("{\"applicationId\":\"a\",\"status\":\"SUBMITTED\"}")));

    assertThat(client.post(APPS, Map.of("walletId", "01711000001"), AGENT).path("applicationId").asString())
        .isEqualTo("a");
    assertThat(client.get(APP, AGENT).path("status").asString()).isEqualTo("SUBMITTED");

    compliance.verify(postRequestedFor(urlEqualTo(APPS))
        .withHeader("Authorization", equalTo("Bearer tok-1"))
        .withHeader("X-Mw-Subject-Type", equalTo("AGENT"))
        .withHeader("X-Mw-Subject-Id", equalTo("01811000017"))
        .withRequestBody(equalToJson("{\"walletId\":\"01711000001\"}")));
    compliance.verify(getRequestedFor(urlEqualTo(APP))
        .withHeader("X-Mw-Subject-Type", equalTo("AGENT"))
        .withHeader("X-Mw-Subject-Id", equalTo("01811000017")));
  }

  @Test
  void mwCompliancesRefusalsPassThrough() {
    compliance.stubFor(get(urlEqualTo(APP)).willReturn(aResponse().withStatus(404)
        .withHeader("Content-Type", "application/json").withBody(ENVELOPE.formatted("MW-VAL-003", false))));
    compliance.stubFor(post(urlEqualTo(APP + "/submit")).willReturn(aResponse().withStatus(504)
        .withHeader("Content-Type", "application/json").withBody(ENVELOPE.formatted("MW-TMO-EC_NID", true))));

    assertThatThrownBy(() -> client.get(APP, AGENT)).isInstanceOfSatisfying(MwException.class, e -> {
      assertThat(e.code()).isEqualTo("MW-VAL-003");
      assertThat(e.status().value()).isEqualTo(404);
    });
    assertThatThrownBy(() -> client.post(APP + "/submit", null, AGENT)).isInstanceOfSatisfying(MwException.class, e -> {
      assertThat(e.code()).isEqualTo("MW-TMO-EC_NID");
      assertThat(e.status().value()).isEqualTo(504);
      assertThat(e.retryable()).isTrue();
    });
  }

  @Test
  void aStaleServiceTokenIsRefreshedOnce() {
    compliance.stubFor(get(urlEqualTo(APP)).inScenario("token").whenScenarioStateIs(Scenario.STARTED)
        .willReturn(aResponse().withStatus(401).withHeader("Content-Type", "application/json")
            .withBody(ENVELOPE.formatted("MW-AUTH-001", false)))
        .willSetStateTo("fresh"));
    compliance.stubFor(get(urlEqualTo(APP)).inScenario("token").whenScenarioStateIs("fresh")
        .willReturn(okJson("{\"applicationId\":\"a\"}")));

    assertThat(client.get(APP, AGENT).path("applicationId").asString()).isEqualTo("a");
    keycloak.verify(2, postRequestedFor(urlEqualTo("/token")));
  }

  @Test
  void anAnswerWithoutAnEnvelopeOrNoAnswerFailsClosed() {
    compliance.stubFor(get(urlEqualTo(APP)).willReturn(aResponse().withStatus(502).withBody("bad gateway")));
    assertFailsClosed(() -> client.get(APP, AGENT));

    compliance.stubFor(get(urlEqualTo(APP)).willReturn(okJson("{}").withFixedDelay(1500)));
    assertFailsClosed(() -> client.get(APP, AGENT));
  }

  static void assertFailsClosed(Runnable call) {
    assertThatThrownBy(call::run).isInstanceOfSatisfying(MwException.class, e -> {
      assertThat(e.code()).isEqualTo("MW-SYS-017");
      assertThat(e.status().value()).isEqualTo(503);
      assertThat(e.retryable()).isTrue();
    });
  }
}
