package mfs.mw.auth.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import mfs.mw.auth.PkiTestAuthority;
import mfs.mw.auth.config.AuthProperties;
import mfs.mw.auth.config.ServiceCertificateManager;
import mfs.mw.auth.config.TlsProperties;
import mfs.mw.auth.dto.CryptoEnvelope;
import mfs.mw.auth.exception.ErrorCodes;
import mfs.mw.auth.exception.MwException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link CryptoClient} against a WireMock mw-crypto over HTTPS that needs a client certificate
 * (ticket 67, D24): this service's certificate comes from the Vault intermediate ({@link
 * PkiTestAuthority}, CN {@code mw-auth}), the WireMock server's from the same intermediate (SAN
 * localhost) and it trusts only that intermediate. The token endpoint is a plain-HTTP WireMock. It
 * proves the bearer token and body, token reuse and the 401 refresh, and fail closed: a 5xx, a
 * timeout, an open breaker or a missing client certificate is {@code MW-SYS-050}, a refusal {@code
 * MW-SYS-051}. Timeouts are shortened (300 ms) so nothing waits long.
 */
class CryptoClientIT {

  static final TlsProperties TLS =
      new TlsProperties(true, "pki_int", "mw-auth", "mw-auth", List.of("mw-auth"), Duration.ofHours(1),
          Duration.ofMinutes(1), "mw-service");
  static final JsonMapper JSON = JsonMapper.builder().build();
  static final String TOKENS = "{\"domain\":\"MSISDN\",\"kid\":\"tokenise-msisdn.v1\",\"tokens\":[\"MSISDN_"
      + "A".repeat(52) + "\"]}";
  static final String ENVELOPE =
      "{\"kekId\":\"kek-column.v1\",\"wrappedKey\":\"d3JhcA==\",\"iv\":\"AAAAAAAAAAAAAAAA\",\"ciphertext\":\"Y2lwaGVy\",\"aad\":\"aWQ=\"}";

  @TempDir static Path stores;
  static WireMockServer cryptoServer;
  static WireMockServer keycloak;
  static ServiceCertificateManager manager;

  CryptoClient client;

  @BeforeAll
  static void start() {
    Path[] files = PkiTestAuthority.writeLocalhostStores("mw-crypto", stores);
    PkiTestAuthority.role("mw-auth", true); // localhost too: InternalApiMtlsIT serves on it
    cryptoServer =
        new WireMockServer(
            options()
                .httpDisabled(true)
                .dynamicHttpsPort()
                .keystorePath(files[0].toString())
                .keystoreType("PKCS12")
                .keystorePassword(PkiTestAuthority.STORE_PASSWORD)
                .keyManagerPassword(PkiTestAuthority.STORE_PASSWORD)
                .needClientAuth(true)
                .trustStorePath(files[1].toString())
                .trustStoreType("PKCS12")
                .trustStorePassword(PkiTestAuthority.STORE_PASSWORD));
    cryptoServer.start();
    keycloak = new WireMockServer(options().dynamicPort());
    keycloak.start();
    manager =
        new ServiceCertificateManager(PkiTestAuthority.vault(), TLS, Clock.systemUTC(), new SimpleMeterRegistry());
  }

  @AfterAll
  static void stop() {
    cryptoServer.stop();
    keycloak.stop();
    manager.destroy();
  }

  @BeforeEach
  void stubs() {
    cryptoServer.resetAll();
    keycloak.resetAll();
    keycloak.stubFor(post(urlEqualTo("/token")).willReturn(okJson("{\"access_token\":\"tok-1\",\"expires_in\":300}")));
    client = client(provider(manager));
  }

  static AuthProperties props() {
    return new AuthProperties(
        new AuthProperties.Crypto(
            URI.create("https://localhost:" + cryptoServer.httpsPort()),
            URI.create("http://127.0.0.1:" + keycloak.port() + "/token"),
            "mw-auth",
            "fake-it-secret",
            Duration.ofMillis(300),
            Duration.ofMillis(500)));
  }

  static ObjectProvider<ServiceCertificateManager> provider(ServiceCertificateManager m) {
    StaticListableBeanFactory beans = new StaticListableBeanFactory(m == null ? Map.of() : Map.of("tls", m));
    return beans.getBeanProvider(ServiceCertificateManager.class);
  }

  static CryptoClient client(ObjectProvider<ServiceCertificateManager> certs) {
    CircuitBreakerRegistry breakers =
        CircuitBreakerRegistry.of(
            CircuitBreakerConfig.custom()
                .slidingWindowSize(4)
                .minimumNumberOfCalls(4)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofMinutes(1))
                .ignoreExceptions(HttpClientErrorException.class)
                .build());
    AuthProperties props = props();
    return new CryptoClient(props, new ServiceTokenClient(props, JSON, Clock.systemUTC()), breakers, certs);
  }

  @Test
  void tokenisesAnMsisdnOverMtlsWithTheServiceToken() {
    cryptoServer.stubFor(post(urlEqualTo("/internal/v1/tokens")).willReturn(okJson(TOKENS)));

    CryptoClient.MsisdnToken t = client.tokeniseMsisdn("01711000001");

    assertThat(t.kid()).isEqualTo("tokenise-msisdn.v1");
    assertThat(t.token()).startsWith("MSISDN_");
    assertThat(t.toString()).doesNotContain("MSISDN_A");
    cryptoServer.verify(
        postRequestedFor(urlEqualTo("/internal/v1/tokens"))
            .withHeader("Authorization", equalTo("Bearer tok-1"))
            .withRequestBody(equalToJson("{\"domain\":\"MSISDN\",\"values\":[\"01711000001\"]}")));
    keycloak.verify(
        postRequestedFor(urlEqualTo("/token"))
            .withHeader("Authorization", matching("Basic .+"))
            .withRequestBody(equalTo("grant_type=client_credentials")));
  }

  @Test
  void encryptsAndDecryptsAnEnvelopeAndReusesTheToken() {
    cryptoServer.stubFor(post(urlEqualTo("/internal/v1/envelope/encrypt")).willReturn(okJson(ENVELOPE)));
    cryptoServer.stubFor(post(urlEqualTo("/internal/v1/envelope/decrypt")).willReturn(okJson("{\"plaintext\":\"MDE3MTEwMDAwMDE=\"}")));

    CryptoEnvelope e = client.encrypt(CryptoClient.KEK_COLUMN, "01711000001".getBytes(StandardCharsets.UTF_8),
        "id".getBytes(StandardCharsets.UTF_8));
    byte[] back = client.decrypt(e);

    assertThat(e.kekId()).isEqualTo("kek-column.v1");
    assertThat(new String(back, StandardCharsets.UTF_8)).isEqualTo("01711000001");
    cryptoServer.verify(
        postRequestedFor(urlEqualTo("/internal/v1/envelope/encrypt"))
            .withRequestBody(equalToJson("{\"purpose\":\"KEK_COLUMN\",\"plaintext\":\"MDE3MTEwMDAwMDE=\",\"aad\":\"aWQ=\"}")));
    keycloak.verify(1, postRequestedFor(urlEqualTo("/token")));
  }

  @Test
  void aStaleTokenIsRefreshedOnceOn401() {
    keycloak.stubFor(post(urlEqualTo("/token")).inScenario("t").whenScenarioStateIs(Scenario.STARTED)
        .willReturn(okJson("{\"access_token\":\"old\",\"expires_in\":300}")).willSetStateTo("second"));
    keycloak.stubFor(post(urlEqualTo("/token")).inScenario("t").whenScenarioStateIs("second")
        .willReturn(okJson("{\"access_token\":\"new\",\"expires_in\":300}")));
    cryptoServer.stubFor(post(urlEqualTo("/internal/v1/tokens")).withHeader("Authorization", equalTo("Bearer old"))
        .willReturn(aResponse().withStatus(401)));
    cryptoServer.stubFor(post(urlEqualTo("/internal/v1/tokens")).withHeader("Authorization", equalTo("Bearer new"))
        .willReturn(okJson(TOKENS)));

    assertThat(client.tokeniseMsisdn("01711000001").kid()).isEqualTo("tokenise-msisdn.v1");
    keycloak.verify(2, postRequestedFor(urlEqualTo("/token")));
  }

  @Test
  void a5xxFailsClosedRetryable() {
    cryptoServer.stubFor(post(urlEqualTo("/internal/v1/tokens")).willReturn(aResponse().withStatus(503)));

    assertThatThrownBy(() -> client.tokeniseMsisdn("01711000001"))
        .isInstanceOfSatisfying(MwException.class, e -> {
          assertThat(e.code()).isEqualTo(ErrorCodes.SYS_CRYPTO_UNAVAILABLE);
          assertThat(e.retryable()).isTrue();
          assertThat(e.status().value()).isEqualTo(503);
        });
  }

  @Test
  void aRefusalIsNotRetryable() {
    cryptoServer.stubFor(post(urlEqualTo("/internal/v1/tokens")).willReturn(aResponse().withStatus(403)));

    assertThatThrownBy(() -> client.tokeniseMsisdn("01711000001"))
        .isInstanceOfSatisfying(MwException.class, e -> {
          assertThat(e.code()).isEqualTo(ErrorCodes.SYS_CRYPTO_REFUSED);
          assertThat(e.retryable()).isFalse();
        });
  }

  @Test
  void aSlowAnswerTimesOutAndFailsClosed() {
    cryptoServer.stubFor(post(urlEqualTo("/internal/v1/tokens")).willReturn(okJson(TOKENS).withFixedDelay(1_000)));

    assertThatThrownBy(() -> client.tokeniseMsisdn("01711000001"))
        .isInstanceOfSatisfying(MwException.class, e -> assertThat(e.code()).isEqualTo(ErrorCodes.SYS_CRYPTO_UNAVAILABLE));
  }

  @Test
  void theBreakerOpensAfterFailuresAndThenCallsNothing() {
    cryptoServer.stubFor(post(urlEqualTo("/internal/v1/tokens")).willReturn(aResponse().withStatus(500)));
    for (int i = 0; i < 4; i++) {
      assertThatThrownBy(() -> client.tokeniseMsisdn("01711000001")).isInstanceOf(MwException.class);
    }
    cryptoServer.resetRequests();

    assertThatThrownBy(() -> client.tokeniseMsisdn("01711000001"))
        .isInstanceOfSatisfying(MwException.class, e -> assertThat(e.code()).isEqualTo(ErrorCodes.SYS_CRYPTO_UNAVAILABLE));
    cryptoServer.verify(0, postRequestedFor(urlEqualTo("/internal/v1/tokens")));
  }

  @Test
  void withoutTheClientCertificateTheHandshakeFailsClosed() {
    cryptoServer.stubFor(post(urlEqualTo("/internal/v1/tokens")).willReturn(okJson(TOKENS)));
    CryptoClient noCert = client(provider(null));

    assertThatThrownBy(() -> noCert.tokeniseMsisdn("01711000001"))
        .isInstanceOfSatisfying(MwException.class, e -> assertThat(e.code()).isEqualTo(ErrorCodes.SYS_CRYPTO_UNAVAILABLE));
    cryptoServer.verify(0, postRequestedFor(urlEqualTo("/internal/v1/tokens")));
  }
}
