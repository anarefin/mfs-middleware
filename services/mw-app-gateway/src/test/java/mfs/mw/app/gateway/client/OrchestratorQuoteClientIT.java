package mfs.mw.app.gateway.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import mfs.mw.app.gateway.config.OrchestratorClientProperties;
import mfs.mw.app.gateway.contracts.OpenApiSchemas;
import mfs.mw.app.gateway.dto.Amount;
import mfs.mw.app.gateway.dto.Quote;
import mfs.mw.app.gateway.dto.QuoteRequest;
import mfs.mw.app.gateway.exception.MwException;
import mfs.mw.app.gateway.exception.OrchestratorUnavailableException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link OrchestratorQuoteClient} against a WireMock mw-orchestrator and Keycloak (ticket 105): the
 * contract-shaped body with the bearer token, the quote read back, a 401 retried once with a fresh
 * token, a 422 envelope passed through, anything else unavailable. No Spring context, no Docker.
 */
class OrchestratorQuoteClientIT {

  static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();
  static final String PATH = "/internal/v1/quotes";
  static final String QUOTE = "{\"txnType\":\"P2P\",\"amount\":{\"valueMinor\":100000,\"currency\":\"BDT\"},"
      + "\"fee\":{\"valueMinor\":1850,\"currency\":\"BDT\"},\"vat\":{\"valueMinor\":241,\"currency\":\"BDT\"},"
      + "\"commission\":{\"valueMinor\":925,\"currency\":\"BDT\"},\"total\":{\"valueMinor\":101850,\"currency\":\"BDT\"},"
      + "\"limits\":{\"allowed\":true,\"remaining\":{\"daily\":{\"valueMinor\":5000000,\"currency\":\"BDT\"}}},"
      + "\"counterparty\":{\"type\":\"WALLET\",\"id\":\"0171****002\",\"name\":\"R.\",\"walletType\":\"CUSTOMER\"}}";
  static final QuoteRequest REQUEST = new QuoteRequest("01711000001", "CUSTOMER", "P2P", "CUSTOMER_APP",
      new Amount(100_000L, "BDT"), new QuoteRequest.Counterparty("WALLET", "01711000002"));

  static WireMockServer orchestrator;
  static WireMockServer keycloak;

  OrchestratorQuoteClient client;

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
    keycloak.stubFor(post(urlEqualTo("/token")).inScenario("tokens").whenScenarioStateIs(Scenario.STARTED)
        .willReturn(okJson("{\"access_token\":\"tok-1\",\"expires_in\":300}")).willSetStateTo("second"));
    keycloak.stubFor(post(urlEqualTo("/token")).inScenario("tokens").whenScenarioStateIs("second")
        .willReturn(okJson("{\"access_token\":\"tok-2\",\"expires_in\":300}")));
    ServiceTokenClient tokens =
        new ServiceTokenClient(OrchestratorQueryClientIT.authProps(keycloak.port()), JSON, Clock.systemUTC());
    client = new OrchestratorQuoteClient(new OrchestratorClientProperties(
        URI.create("http://127.0.0.1:" + orchestrator.port()), Duration.ofMillis(300), Duration.ofMillis(300)), tokens, JSON);
  }

  @Test
  void sendsTheContractBodyWithTheBearerTokenAndReadsTheQuote() {
    orchestrator.stubFor(post(urlEqualTo(PATH)).willReturn(okJson(QUOTE)));

    Quote q = client.quote(REQUEST);

    assertThat(q.fee().valueMinor()).isEqualTo(1_850);
    assertThat(q.limits().remaining().daily().valueMinor()).isEqualTo(5_000_000);
    assertThat(q.counterparty().name()).isEqualTo("R.");
    orchestrator.verify(postRequestedFor(urlEqualTo(PATH)).withHeader("Authorization", equalTo("Bearer tok-1")));
    byte[] sent = orchestrator.getAllServeEvents().get(0).getRequest().getBody();
    assertThat(OpenApiSchemas.component("api/mw-orchestrator/txn-query.v1.openapi.yaml", "QuoteRequest")
        .validate(JSON.readTree(sent))).isEmpty();
    assertThat(OpenApiSchemas.component("api/mw-orchestrator/txn-query.v1.openapi.yaml", "Quote")
        .validate(JSON.readTree(QUOTE))).as("the stub is contract-shaped").isEmpty();
  }

  @Test
  void a401IsAskedOnceMoreWithAFreshToken() {
    orchestrator.stubFor(post(urlEqualTo(PATH)).withHeader("Authorization", equalTo("Bearer tok-1"))
        .willReturn(aResponse().withStatus(401)));
    orchestrator.stubFor(post(urlEqualTo(PATH)).withHeader("Authorization", equalTo("Bearer tok-2"))
        .willReturn(okJson(QUOTE)));

    assertThat(client.quote(REQUEST).txnType()).isEqualTo("P2P");
  }

  @Test
  void aCoreRefusalPassesThroughWithItsCodeAndStatus() {
    orchestrator.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(422)
        .withHeader("Content-Type", "application/json")
        .withBody("{\"code\":\"MW-CORE-CREDIT_WALLET_NOT_FOUND\",\"message\":\"The receiving wallet does not exist\","
            + "\"messageBn\":\"প্রাপকের ওয়ালেট পাওয়া যায়নি\",\"correlationId\":\"c\",\"retryable\":false,\"details\":[]}")));

    assertThatThrownBy(() -> client.quote(REQUEST)).isInstanceOfSatisfying(MwException.class, e -> {
      assertThat(e.code()).isEqualTo("MW-CORE-CREDIT_WALLET_NOT_FOUND");
      assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
      assertThat(e.messageBn()).isNotBlank();
    });
  }

  @Test
  void anyOtherAnswerOrNoAnswerIsUnavailable() {
    orchestrator.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(503)));
    assertThatThrownBy(() -> client.quote(REQUEST)).isInstanceOf(OrchestratorUnavailableException.class);

    orchestrator.stubFor(post(urlEqualTo(PATH)).willReturn(okJson(QUOTE).withFixedDelay(1_000)));
    assertThatThrownBy(() -> client.quote(REQUEST)).isInstanceOf(OrchestratorUnavailableException.class);

    orchestrator.stubFor(post(urlEqualTo(PATH)).willReturn(okJson("not json")));
    assertThatThrownBy(() -> client.quote(REQUEST)).isInstanceOf(OrchestratorUnavailableException.class);
  }
}
