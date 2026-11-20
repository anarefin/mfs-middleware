package mfs.mw.adapter.atm.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.time.Clock;
import java.time.Duration;
import mfs.mw.adapter.atm.config.AuthClientProperties;
import mfs.mw.adapter.atm.dto.AtmTokenVerdict;
import mfs.mw.adapter.atm.exception.MwException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;

/**
 * mw-auth's ATM token check (ticket 120b2; contract {@code api/mw-auth/auth-internal.v1.openapi.yaml}
 * {@code POST /internal/v1/atm-tokens/verifications}) against a WireMock mw-auth and token endpoint:
 * the service token, the body, the verdicts (spent: the wallet; refused: DECLINED with mw-auth's
 * code), one token refresh, and fail closed on anything else. The token is never logged.
 */
@ExtendWith(OutputCaptureExtension.class)
class AtmTokenClientTest {

  static final String VERIFY = "/internal/v1/atm-tokens/verifications";
  static final JsonMapper JSON = JsonMapper.builder().build();
  static WireMockServer server;

  AtmTokenClient client;

  @BeforeAll
  static void start() {
    server = new WireMockServer(options().dynamicPort());
    server.start();
  }

  @AfterAll
  static void stop() {
    server.stop();
  }

  @BeforeEach
  void setUp() {
    server.resetAll();
    server.stubFor(post(urlEqualTo("/token")).willReturn(okJson("{\"access_token\":\"tok-1\",\"expires_in\":300}")));
    AuthClientProperties props = new AuthClientProperties(
        "http://127.0.0.1:" + server.port(), "http://127.0.0.1:" + server.port() + "/token", "mw-adapter-atm",
        "fake-test-secret", null, Duration.ofMillis(500), Duration.ofMillis(500));
    client = new AtmTokenClient(props, new ServiceTokenClient(props, JSON, Clock.systemUTC()), JSON, null);
  }

  static String envelope(String code) {
    return "{\"code\":\"" + code + "\",\"message\":\"m\",\"correlationId\":\"c\",\"retryable\":false,\"details\":[]}";
  }

  @Test
  void theRightTokenIsSpentAndAnswersTheWallet(CapturedOutput output) {
    server.stubFor(post(urlEqualTo(VERIFY)).willReturn(okJson("{\"walletId\":\"01711000001\"}")));

    AtmTokenVerdict v = client.verify("01711000001", 50_000, "654321");

    assertThat(v).isEqualTo(AtmTokenVerdict.accepted("01711000001"));
    server.verify(postRequestedFor(urlEqualTo(VERIFY))
        .withHeader("Authorization", equalTo("Bearer tok-1"))
        .withRequestBody(equalToJson(
            "{\"msisdn\":\"01711000001\",\"amount\":50000,\"currency\":\"BDT\",\"token\":\"654321\"}")));
    assertThat(output.getAll()).doesNotContain("654321");
  }

  @Test
  void aWrongExpiredLockedTokenOrABlockedWalletIsDeclined() {
    for (String[] c : new String[][] {
        {"401", "MW-AUTH-010"}, {"401", "MW-AUTH-011"}, {"401", "MW-AUTH-012"}, {"403", "MW-AUTH-043"},
        {"423", "MW-AUTH-041"}}) {
      server.stubFor(post(urlEqualTo(VERIFY)).willReturn(aResponse().withStatus(Integer.parseInt(c[0]))
          .withHeader("Content-Type", "application/json").withBody(envelope(c[1]))));
      assertThat(client.verify("01711000001", 50_000, "111111")).as(c[1]).isEqualTo(AtmTokenVerdict.declined(c[1]));
    }
  }

  @Test
  void aRefusedServiceTokenIsRefreshedOnce() {
    server.stubFor(post(urlEqualTo(VERIFY)).inScenario("t").whenScenarioStateIs(Scenario.STARTED)
        .willReturn(aResponse().withStatus(401).withHeader("Content-Type", "application/json")
            .withBody(envelope("MW-AUTH-001"))).willSetStateTo("again"));
    server.stubFor(post(urlEqualTo(VERIFY)).inScenario("t").whenScenarioStateIs("again")
        .willReturn(okJson("{\"walletId\":\"01711000001\"}")));

    assertThat(client.verify("01711000001", 50_000, "654321").accepted()).isTrue();
    server.verify(2, postRequestedFor(urlEqualTo("/token")));
  }

  @Test
  void notAllowedAnOutageOrABadAnswerFailsClosed() {
    String[][] answers = {
        {"403", envelope("MW-AUTH-002")}, {"400", envelope("MW-VAL-001")}, {"503", envelope("MW-SYS-050")},
        {"200", "{}"}, {"500", "oops"}};
    for (String[] a : answers) {
      server.stubFor(post(urlEqualTo(VERIFY)).willReturn(aResponse().withStatus(Integer.parseInt(a[0]))
          .withHeader("Content-Type", "application/json").withBody(a[1])));
      assertThatThrownBy(() -> client.verify("01711000001", 50_000, "654321")).as(a[0] + " " + a[1])
          .isInstanceOfSatisfying(MwException.class, e -> {
            assertThat(e.code()).isEqualTo(AtmTokenClient.UNAVAILABLE);
            assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
          });
    }
  }

  @Test
  void aSlowMwAuthTimesOutAndFailsClosed() {
    server.stubFor(post(urlEqualTo(VERIFY)).willReturn(okJson("{\"walletId\":\"01711000001\"}").withFixedDelay(1500)));

    assertThatThrownBy(() -> client.verify("01711000001", 50_000, "654321"))
        .isInstanceOfSatisfying(MwException.class, e -> assertThat(e.code()).isEqualTo(AtmTokenClient.UNAVAILABLE));
  }
}
