package mfs.mw.app.gateway.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import mfs.mw.app.gateway.config.AuthClientProperties;
import mfs.mw.app.gateway.config.OrchestratorClientProperties;
import mfs.mw.app.gateway.dto.QueryTxnPage;
import mfs.mw.app.gateway.exception.OrchestratorUnavailableException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link OrchestratorQueryClient} against a WireMock mw-orchestrator query API and a WireMock
 * Keycloak token endpoint (ADR D25): the bearer token of client mw-app-gateway, the wallet always
 * sent as {@code walletId}, the filters, one fresh-token retry on 401, and every other failure
 * (another status, unreadable answer, timeout, unreachable, no token) is {@link
 * OrchestratorUnavailableException}. No Spring context, no Docker (the Resilience4j retry and
 * breaker around it are configuration, see application.yml).
 */
class OrchestratorQueryClientIT {

  static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();
  static final String PATH = "/internal/v1/transactions";
  static final String PAGE = "{\"items\":[{\"txnId\":\"019a4f6e-1c2d-7a3b-8c4d-5e6f7a8b9c0d\",\"direction\":\"IN\","
      + "\"txnType\":\"CASH_IN\",\"channel\":\"AGENT_APP\",\"status\":\"COMPLETED\",\"createdAt\":\"2026-11-16T06:00:00Z\","
      + "\"updatedAt\":\"2026-11-16T06:00:02Z\",\"amount\":{\"valueMinor\":3000,\"currency\":\"BDT\"},"
      + "\"debitParty\":{\"type\":\"WALLET\",\"id\":\"0181****001\",\"walletType\":\"AGENT\"},"
      + "\"creditParty\":{\"type\":\"WALLET\",\"id\":\"0171****001\",\"walletType\":\"CUSTOMER\"},"
      + "\"coreRef\":\"CORE-7\"}],\"limit\":21,\"offset\":0}";

  static WireMockServer orchestrator;
  static WireMockServer keycloak;

  ServiceTokenClient tokens;
  OrchestratorQueryClient client;

  @BeforeAll
  static void start() {
    orchestrator = new WireMockServer(options().dynamicPort());
    orchestrator.start();
    keycloak = new WireMockServer(options().dynamicPort());
    keycloak.start();
  }

  @AfterAll
  static void stop() {
    orchestrator.stop();
    keycloak.stop();
  }

  @BeforeEach
  void stubs() {
    orchestrator.resetAll();
    keycloak.resetAll();
    keycloak.stubFor(post(urlEqualTo("/token"))
        .inScenario("tokens").whenScenarioStateIs(Scenario.STARTED)
        .willReturn(okJson("{\"access_token\":\"tok-1\",\"expires_in\":300}")).willSetStateTo("second"));
    keycloak.stubFor(post(urlEqualTo("/token"))
        .inScenario("tokens").whenScenarioStateIs("second")
        .willReturn(okJson("{\"access_token\":\"tok-2\",\"expires_in\":300}")));
    tokens = new ServiceTokenClient(authProps(keycloak.port()), JSON, Clock.systemUTC());
    client = client("http://127.0.0.1:" + orchestrator.port());
  }

  static AuthClientProperties authProps(int keycloakPort) {
    return new AuthClientProperties(
        URI.create("https://mw-auth.test:8443"), URI.create("http://127.0.0.1:" + keycloakPort + "/token"),
        "mw-app-gateway", "fake-test-secret", Duration.ofMillis(500), Duration.ofMillis(500), Duration.ofMinutes(5));
  }

  OrchestratorQueryClient client(String base) {
    return new OrchestratorQueryClient(
        new OrchestratorClientProperties(URI.create(base), Duration.ofMillis(300), Duration.ofMillis(300)), JSON, tokens);
  }

  static Map<String, Object> filters() {
    Map<String, Object> f = new LinkedHashMap<>();
    f.put("direction", "IN");
    f.put("txnType", null);
    f.put("from", Instant.parse("2026-11-01T00:00:00Z"));
    f.put("limit", 21);
    f.put("offset", 0);
    return f;
  }

  @Test
  void asksForTheWalletWithTheBearerTokenAndReadsThePage() {
    orchestrator.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson(PAGE)));

    QueryTxnPage page = client.walletHistory("01711000001", filters());

    assertThat(page.items()).hasSize(1);
    assertThat(page.items().get(0).direction()).isEqualTo("IN");
    assertThat(page.items().get(0).debitParty().id()).isEqualTo("0181****001");
    assertThat(page.items().get(0).amount().valueMinor()).isEqualTo(3_000L);
    orchestrator.verify(getRequestedFor(urlEqualTo(
            PATH + "?walletId=01711000001&direction=IN&from=2026-11-01T00%3A00%3A00Z&limit=21&offset=0"))
        .withHeader("Authorization", equalTo("Bearer tok-1")));
    keycloak.verify(1, postRequestedFor(urlEqualTo("/token")));
  }

  @Test
  void aWalletIdAmongTheFiltersNeverReplacesTheSignedInWallet() {
    orchestrator.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson(PAGE)));
    Map<String, Object> f = filters();
    f.put("walletId", "01711000009");

    client.walletHistory("01711000001", f);

    orchestrator.verify(getRequestedFor(urlPathEqualTo(PATH)).withQueryParam("walletId", equalTo("01711000001")));
    assertThat(orchestrator.getAllServeEvents().get(0).getRequest().getUrl()).doesNotContain("01711000009");
  }

  @Test
  void a401IsAskedOnceMoreWithAFreshToken() {
    orchestrator.stubFor(get(urlPathEqualTo(PATH)).withHeader("Authorization", equalTo("Bearer tok-1"))
        .willReturn(aResponse().withStatus(401)));
    orchestrator.stubFor(get(urlPathEqualTo(PATH)).withHeader("Authorization", equalTo("Bearer tok-2"))
        .willReturn(okJson(PAGE)));

    assertThat(client.walletHistory("01711000001", filters()).items()).hasSize(1);
    keycloak.verify(2, postRequestedFor(urlEqualTo("/token")));
  }

  @Test
  void anyOtherAnswerOrAnUnreadableBodyIsUnavailable() {
    for (int status : new int[] {400, 403, 500, 503}) {
      orchestrator.resetAll();
      orchestrator.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(status)
          .withBody("{\"code\":\"MW-AUTH-002\",\"message\":\"Not allowed\",\"correlationId\":\"c\",\"retryable\":false,\"details\":[]}")));
      assertThatThrownBy(() -> client.walletHistory("01711000001", filters()))
          .as("status %d", status)
          .isInstanceOf(OrchestratorUnavailableException.class);
    }
    orchestrator.resetAll();
    orchestrator.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson("{not json")));
    assertThatThrownBy(() -> client.walletHistory("01711000001", filters()))
        .isInstanceOf(OrchestratorUnavailableException.class);
    // a 401 twice in a row
    orchestrator.resetAll();
    orchestrator.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(401)));
    assertThatThrownBy(() -> client.walletHistory("01711000001", filters()))
        .isInstanceOf(OrchestratorUnavailableException.class);
  }

  @Test
  void aSlowOrUnreachableOrchestratorOrNoTokenIsUnavailable() {
    orchestrator.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson(PAGE).withFixedDelay(1_000)));
    assertThatThrownBy(() -> client.walletHistory("01711000001", filters()))
        .isInstanceOf(OrchestratorUnavailableException.class);

    assertThatThrownBy(() -> client("http://127.0.0.1:1").walletHistory("01711000001", filters()))
        .isInstanceOf(OrchestratorUnavailableException.class);

    keycloak.resetAll();
    keycloak.stubFor(post(urlEqualTo("/token")).willReturn(aResponse().withStatus(503)));
    tokens.invalidate();
    assertThatThrownBy(() -> client.walletHistory("01711000001", filters()))
        .isInstanceOf(OrchestratorUnavailableException.class);
  }
}
