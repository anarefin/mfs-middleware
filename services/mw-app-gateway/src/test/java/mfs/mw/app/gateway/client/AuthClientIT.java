package mfs.mw.app.gateway.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
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
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import mfs.mw.app.gateway.PkiTestAuthority;
import mfs.mw.app.gateway.config.AuthClientProperties;
import mfs.mw.app.gateway.config.ServiceCertificateManager;
import mfs.mw.app.gateway.config.TlsProperties;
import mfs.mw.app.gateway.dto.BoundDevice;
import mfs.mw.app.gateway.exception.MwException;
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
 * {@link AuthClient} against a WireMock mw-auth internal port over HTTPS that needs a client
 * certificate (ticket 68, D24): this service's certificate comes from the OpenBao intermediate
 * ({@link PkiTestAuthority}, CN {@code mw-app-gateway}), the WireMock server's from the same
 * intermediate (SAN localhost) and it trusts only that intermediate. The token endpoint is a
 * plain-HTTP WireMock. It proves the bearer token, the 404 answers (not bound, no session), the 401
 * refresh, and fail closed: a 5xx, another refusal, a timeout, an open breaker or a missing client
 * certificate is {@code MW-SYS-012} (503, retryable). Timeouts are shortened (300 ms).
 */
class AuthClientIT {

  static final TlsProperties TLS =
      new TlsProperties(true, "pki_int", "mw-app-gateway", "mw-app-gateway", List.of("mw-app-gateway"),
          Duration.ofHours(1), Duration.ofMinutes(1), "mw-service");
  static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();
  static final String DEVICE = "/internal/v1/devices/device-0001";
  static final String SESSION = "/internal/v1/sessions/01711000001/device-0001";
  static final String BOUND = "{\"deviceId\":\"device-0001\",\"walletId\":\"01711000001\","
      + "\"publicKey\":\"MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE\",\"boundAt\":\"2026-11-14T06:00:00Z\"}";
  static final String LIVE = "{\"walletId\":\"01711000001\",\"deviceId\":\"device-0001\",\"idleTimeoutSeconds\":300,"
      + "\"deviceKeyFingerprint\":\"abababababababababababababababababababababababababababababababab\",\"boundAt\":\"2026-11-14T06:00:00Z\"}";
  static final String NOT_BOUND = "{\"code\":\"MW-AUTH-003\",\"message\":\"Device not bound\",\"correlationId\":\"c\","
      + "\"retryable\":false,\"details\":[]}";

  @TempDir static Path stores;
  static WireMockServer authServer;
  static WireMockServer keycloak;
  static ServiceCertificateManager manager;

  AuthClient client;

  @BeforeAll
  static void start() {
    Path[] files = PkiTestAuthority.writeLocalhostStores("mw-auth", stores);
    PkiTestAuthority.role("mw-app-gateway", true);
    authServer =
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
    authServer.start();
    keycloak = new WireMockServer(options().dynamicPort());
    keycloak.start();
    manager =
        new ServiceCertificateManager(PkiTestAuthority.vault(), TLS, Clock.systemUTC(), new SimpleMeterRegistry());
  }

  @AfterAll
  static void stop() {
    authServer.stop();
    keycloak.stop();
    manager.destroy();
  }

  @BeforeEach
  void stubs() {
    authServer.resetAll();
    keycloak.resetAll();
    keycloak.stubFor(post(urlEqualTo("/token")).willReturn(okJson("{\"access_token\":\"tok-1\",\"expires_in\":300}")));
    client = client(provider(manager));
  }

  static AuthClientProperties props() {
    return new AuthClientProperties(
        URI.create("https://localhost:" + authServer.httpsPort()),
        URI.create("http://127.0.0.1:" + keycloak.port() + "/token"),
        "mw-app-gateway",
        "fake-it-secret",
        Duration.ofMillis(300),
        Duration.ofMillis(500));
  }

  static ObjectProvider<ServiceCertificateManager> provider(ServiceCertificateManager m) {
    StaticListableBeanFactory beans = new StaticListableBeanFactory(m == null ? Map.of() : Map.of("tls", m));
    return beans.getBeanProvider(ServiceCertificateManager.class);
  }

  static AuthClient client(ObjectProvider<ServiceCertificateManager> certs) {
    CircuitBreakerRegistry breakers =
        CircuitBreakerRegistry.of(
            CircuitBreakerConfig.custom()
                .slidingWindowSize(4)
                .minimumNumberOfCalls(4)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofMinutes(1))
                .ignoreExceptions(HttpClientErrorException.class)
                .build());
    AuthClientProperties props = props();
    return new AuthClient(props, new ServiceTokenClient(props, JSON, Clock.systemUTC()), breakers, certs);
  }

  static void assertFailsClosed(Runnable call) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(MwException.class, e -> {
          assertThat(e.code()).isEqualTo("MW-SYS-012");
          assertThat(e.retryable()).isTrue();
          assertThat(e.status().value()).isEqualTo(503);
        });
  }

  @Test
  void readsABoundDeviceOverMtlsWithTheServiceToken() {
    authServer.stubFor(get(urlEqualTo(DEVICE)).willReturn(okJson(BOUND)));

    BoundDevice d = client.device("device-0001").orElseThrow();

    assertThat(d.walletId()).isEqualTo("01711000001");
    assertThat(d.publicKey()).startsWith("MFkw");
    authServer.verify(getRequestedFor(urlEqualTo(DEVICE)).withHeader("Authorization", equalTo("Bearer tok-1")));
    keycloak.verify(
        postRequestedFor(urlEqualTo("/token"))
            .withHeader("Authorization", matching("Basic .+"))
            .withRequestBody(equalTo("grant_type=client_credentials")));
  }

  @Test
  void a404IsAnAnswerNotAnOutage() {
    authServer.stubFor(get(urlEqualTo(DEVICE)).willReturn(aResponse().withStatus(404)
        .withHeader("Content-Type", "application/json").withBody(NOT_BOUND)));
    authServer.stubFor(get(urlEqualTo(SESSION)).willReturn(aResponse().withStatus(404)));

    for (int i = 0; i < 6; i++) { // never opens the breaker
      assertThat(client.device("device-0001")).isEmpty();
      assertThat(client.session("01711000001", "device-0001")).isEmpty();
    }
  }

  @Test
  void aLiveSessionIsAnsweredWithTheKeyFingerprintAndTheTokenIsReused() {
    authServer.stubFor(get(urlEqualTo(SESSION)).willReturn(okJson(LIVE)));

    assertThat(client.session("01711000001", "device-0001")).get()
        .extracting(s -> s.deviceKeyFingerprint()).isEqualTo("abababababababababababababababababababababababababababababababab");
    assertThat(client.session("01711000001", "device-0001")).isPresent();
    keycloak.verify(1, postRequestedFor(urlEqualTo("/token")));
  }

  @Test
  void aStaleTokenIsRefreshedOnceOn401() {
    keycloak.stubFor(post(urlEqualTo("/token")).inScenario("t").whenScenarioStateIs(Scenario.STARTED)
        .willReturn(okJson("{\"access_token\":\"old\",\"expires_in\":300}")).willSetStateTo("second"));
    keycloak.stubFor(post(urlEqualTo("/token")).inScenario("t").whenScenarioStateIs("second")
        .willReturn(okJson("{\"access_token\":\"new\",\"expires_in\":300}")));
    authServer.stubFor(get(urlEqualTo(SESSION)).withHeader("Authorization", equalTo("Bearer old"))
        .willReturn(aResponse().withStatus(401)));
    authServer.stubFor(get(urlEqualTo(SESSION)).withHeader("Authorization", equalTo("Bearer new"))
        .willReturn(okJson(LIVE)));

    assertThat(client.session("01711000001", "device-0001")).isPresent();
    keycloak.verify(2, postRequestedFor(urlEqualTo("/token")));
  }

  @Test
  void a5xxOrARefusalFailsClosed() {
    authServer.stubFor(get(urlEqualTo(DEVICE)).willReturn(aResponse().withStatus(503)));
    authServer.stubFor(get(urlEqualTo(SESSION)).willReturn(aResponse().withStatus(403)));

    assertFailsClosed(() -> client.device("device-0001"));
    assertFailsClosed(() -> client.session("01711000001", "device-0001"));
  }

  @Test
  void aSlowAnswerTimesOutAndFailsClosed() {
    authServer.stubFor(get(urlEqualTo(SESSION)).willReturn(okJson(LIVE).withFixedDelay(1_000)));

    assertFailsClosed(() -> client.session("01711000001", "device-0001"));
  }

  @Test
  void theBreakerOpensAfterFailuresAndThenCallsNothing() {
    authServer.stubFor(get(urlEqualTo(DEVICE)).willReturn(aResponse().withStatus(500)));
    for (int i = 0; i < 4; i++) {
      assertThatThrownBy(() -> client.device("device-0001")).isInstanceOf(MwException.class);
    }
    authServer.resetRequests();

    assertFailsClosed(() -> client.device("device-0001"));
    authServer.verify(0, getRequestedFor(urlEqualTo(DEVICE)));
  }

  @Test
  void withoutTheClientCertificateTheHandshakeFailsClosed() {
    authServer.stubFor(get(urlEqualTo(DEVICE)).willReturn(okJson(BOUND)));
    AuthClient noCert = client(provider(null));

    assertFailsClosed(() -> noCert.device("device-0001"));
    authServer.verify(0, getRequestedFor(urlEqualTo(DEVICE)));
  }
}
