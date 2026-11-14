package mfs.mw.ussd.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
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
import mfs.mw.ussd.PkiTestAuthority;
import mfs.mw.ussd.config.AuthClientProperties;
import mfs.mw.ussd.config.ServiceCertificateManager;
import mfs.mw.ussd.config.TlsProperties;
import mfs.mw.ussd.dto.EncryptedPin;
import mfs.mw.ussd.dto.PinKey;
import mfs.mw.ussd.dto.TxnAuthorisation;
import mfs.mw.ussd.dto.TxnAuthorisationRequest;
import mfs.mw.ussd.exception.AuthRefusedException;
import mfs.mw.ussd.exception.MwException;
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
 * certificate (ticket 72, D24): this service's certificate comes from the OpenBao intermediate
 * ({@link PkiTestAuthority}, CN {@code mw-ussd}), the WireMock server's from the same intermediate
 * (SAN localhost) and it trusts only that intermediate. The token endpoint and the public pin-key
 * endpoint are a plain-HTTP WireMock. It proves the bearer token and the body of the transaction
 * authorisation, mw-auth's refusals as answers (a wrong PIN never retried, never opens the
 * breaker), the 401 MW-AUTH-001 token refresh, the transport key, and fail closed: a 5xx, a timeout,
 * an open breaker or a missing client certificate is {@code MW-SYS-012} (503, retryable).
 */
class AuthClientIT {

  static final TlsProperties TLS =
      new TlsProperties(true, "pki_int", "mw-ussd", "mw-ussd", List.of("mw-ussd"),
          Duration.ofHours(1), Duration.ofMinutes(1), "mw-service");
  static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();
  static final String TXN_AUTH = "/internal/v1/txn-authorisations";
  static final String ASSERTION = "eyJhbGciOiJFUzI1NiJ9.eyJqdGkiOiJ0In0.c2lnbmF0dXJl";
  static final String AUTHORISED =
      "{\"assertion\":\"" + ASSERTION + "\",\"expiresAt\":\"2026-11-15T10:01:00Z\",\"newDevice\":false}";
  static final TxnAuthorisationRequest BODY =
      new TxnAuthorisationRequest("01711000001", "USSD", new EncryptedPin("PIN_TRANSPORT.v1", "Y2lwaGVy"),
          new TxnAuthorisationRequest.Txn("P2P", 50_000, "BDT",
              new TxnAuthorisationRequest.TxnParty("WALLET", "01711000001"),
              new TxnAuthorisationRequest.TxnParty("WALLET", "01711000002"), "k".repeat(64)));

  @TempDir static Path stores;
  static WireMockServer authServer;
  static WireMockServer plain;
  static ServiceCertificateManager manager;

  AuthClient client;

  @BeforeAll
  static void start() {
    Path[] files = PkiTestAuthority.writeLocalhostStores("mw-auth", stores);
    PkiTestAuthority.role("mw-ussd", true);
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
    plain = new WireMockServer(options().dynamicPort());
    plain.start();
    manager =
        new ServiceCertificateManager(PkiTestAuthority.vault(), TLS, Clock.systemUTC(), new SimpleMeterRegistry());
  }

  @AfterAll
  static void stop() {
    authServer.stop();
    plain.stop();
    manager.destroy();
  }

  @BeforeEach
  void stubs() {
    authServer.resetAll();
    plain.resetAll();
    plain.stubFor(post(urlEqualTo("/token")).willReturn(okJson("{\"access_token\":\"tok-1\",\"expires_in\":300}")));
    client = client(provider(manager));
  }

  static AuthClientProperties props() {
    return new AuthClientProperties(
        URI.create("https://localhost:" + authServer.httpsPort()),
        URI.create("http://127.0.0.1:" + plain.port() + "/token"),
        "mw-ussd",
        "fake-it-secret",
        Duration.ofMillis(300),
        Duration.ofMillis(500),
        Duration.ofMinutes(10));
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
    return new AuthClient(props, new ServiceTokenClient(props, JSON, Clock.systemUTC()), breakers, certs, JSON);
  }

  static String envelope(String code, String details) {
    return "{\"code\":\"" + code + "\",\"message\":\"refused\",\"correlationId\":\"c\",\"retryable\":false,"
        + "\"details\":" + details + "}";
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
  void authorisesOverMtlsWithTheServiceTokenAndTheContractBody() {
    authServer.stubFor(post(urlEqualTo(TXN_AUTH)).willReturn(okJson(AUTHORISED)));

    TxnAuthorisation a = client.authorise(BODY);

    assertThat(a.assertion()).isEqualTo(ASSERTION);
    authServer.verify(postRequestedFor(urlEqualTo(TXN_AUTH))
        .withHeader("Authorization", equalTo("Bearer tok-1"))
        .withRequestBody(equalToJson("{\"msisdn\":\"01711000001\",\"channel\":\"USSD\","
            + "\"encryptedPin\":{\"keyId\":\"PIN_TRANSPORT.v1\",\"ciphertext\":\"Y2lwaGVy\"},"
            + "\"txn\":{\"txnType\":\"P2P\",\"amount\":50000,\"currency\":\"BDT\","
            + "\"payer\":{\"type\":\"WALLET\",\"id\":\"01711000001\"},\"payee\":{\"type\":\"WALLET\",\"id\":\"01711000002\"},"
            + "\"idempotencyKey\":\"" + "k".repeat(64) + "\"}}")));
    plain.verify(
        postRequestedFor(urlEqualTo("/token"))
            .withHeader("Authorization", matching("Basic .+"))
            .withRequestBody(equalTo("grant_type=client_credentials")));
  }

  @Test
  void aWrongPinIsAnAnswerSentOnceAndNeverOpensTheBreaker() {
    authServer.stubFor(post(urlEqualTo(TXN_AUTH)).willReturn(aResponse().withStatus(401)
        .withHeader("Content-Type", "application/json")
        .withBody(envelope("MW-AUTH-040", "[{\"field\":\"triesLeft\",\"issue\":\"2\"}]"))));

    for (int i = 0; i < 6; i++) {
      assertThatThrownBy(() -> client.authorise(BODY))
          .isInstanceOfSatisfying(AuthRefusedException.class, e -> {
            assertThat(e.code()).isEqualTo("MW-AUTH-040");
            assertThat(e.envelope().details()).extracting(d -> d.issue()).containsExactly("2");
          });
    }
    // one request per call: a wrong PIN is never retried with a fresh token
    authServer.verify(6, postRequestedFor(urlEqualTo(TXN_AUTH)));
    plain.verify(1, postRequestedFor(urlEqualTo("/token")));
  }

  @Test
  void locksAreAnswers() {
    authServer.stubFor(post(urlEqualTo(TXN_AUTH)).willReturn(aResponse().withStatus(423)
        .withHeader("Content-Type", "application/json")
        .withBody(envelope("MW-AUTH-041", "[{\"field\":\"retryAfter\",\"issue\":\"60\"}]"))));

    assertThatThrownBy(() -> client.authorise(BODY))
        .isInstanceOfSatisfying(AuthRefusedException.class, e -> assertThat(e.status()).isEqualTo(423));
  }

  @Test
  void aRefusedPinBlockIsAnAnswer() {
    authServer.stubFor(post(urlEqualTo(TXN_AUTH)).willReturn(aResponse().withStatus(503)
        .withHeader("Content-Type", "application/json").withBody(envelope("MW-SYS-051", "[]"))));

    assertThatThrownBy(() -> client.authorise(BODY))
        .isInstanceOfSatisfying(AuthRefusedException.class, e -> assertThat(e.code()).isEqualTo("MW-SYS-051"));
  }

  @Test
  void aStaleTokenIsRefreshedOnceOn401TokenRefused() {
    plain.stubFor(post(urlEqualTo("/token")).inScenario("t").whenScenarioStateIs(Scenario.STARTED)
        .willReturn(okJson("{\"access_token\":\"old\",\"expires_in\":300}")).willSetStateTo("second"));
    plain.stubFor(post(urlEqualTo("/token")).inScenario("t").whenScenarioStateIs("second")
        .willReturn(okJson("{\"access_token\":\"new\",\"expires_in\":300}")));
    authServer.stubFor(post(urlEqualTo(TXN_AUTH)).withHeader("Authorization", equalTo("Bearer old"))
        .willReturn(aResponse().withStatus(401).withHeader("Content-Type", "application/json")
            .withBody(envelope("MW-AUTH-001", "[]"))));
    authServer.stubFor(post(urlEqualTo(TXN_AUTH)).withHeader("Authorization", equalTo("Bearer new"))
        .willReturn(okJson(AUTHORISED)));

    assertThat(client.authorise(BODY).assertion()).isEqualTo(ASSERTION);
    plain.verify(2, postRequestedFor(urlEqualTo("/token")));
  }

  static final String PIN_KEY = "/internal/v1/pin-key";

  static String pinKey(String purpose) {
    return "{\"keyId\":\"PIN_TRANSPORT.v2\",\"algorithm\":\"RSA-OAEP-SHA-256\",\"publicKeyPem\":\"-----BEGIN PUBLIC KEY-----\","
        + "\"purpose\":\"" + purpose + "\"}";
  }

  /** Security review of ticket 72: the transport key over the internal mTLS port with the service token. */
  @Test
  void theTransportKeyComesOverMtlsWithTheServiceToken() {
    authServer.stubFor(get(urlEqualTo(PIN_KEY)).willReturn(okJson(pinKey("PIN_TRANSPORT"))));

    PinKey k = client.pinKey();

    assertThat(k.keyId()).isEqualTo("PIN_TRANSPORT.v2");
    authServer.verify(getRequestedFor(urlEqualTo(PIN_KEY)).withHeader("Authorization", equalTo("Bearer tok-1")));
  }

  @Test
  void aKeyOfAnotherPurposeOrWithoutOneIsRefused() {
    authServer.stubFor(get(urlEqualTo(PIN_KEY)).willReturn(okJson(pinKey("JWT_SIGN"))));
    assertFailsClosed(() -> client.pinKey());

    authServer.stubFor(get(urlEqualTo(PIN_KEY)).willReturn(okJson(
        "{\"keyId\":\"k.v1\",\"algorithm\":\"RSA-OAEP-SHA-256\",\"publicKeyPem\":\"-----BEGIN PUBLIC KEY-----\"}")));
    assertFailsClosed(() -> client.pinKey());
  }

  @Test
  void withoutTheClientCertificateNoTransportKeyIsRead() {
    authServer.stubFor(get(urlEqualTo(PIN_KEY)).willReturn(okJson(pinKey("PIN_TRANSPORT"))));
    authServer.resetRequests();
    assertFailsClosed(() -> client(provider(null)).pinKey());
    authServer.verify(0, getRequestedFor(urlEqualTo(PIN_KEY)));
  }

  @Test
  void a5xxOrAnAnswerWithoutAnAssertionFailsClosed() {
    authServer.stubFor(post(urlEqualTo(TXN_AUTH)).willReturn(aResponse().withStatus(500)));
    assertFailsClosed(() -> client.authorise(BODY));

    authServer.stubFor(post(urlEqualTo(TXN_AUTH)).willReturn(okJson("{\"expiresAt\":\"2026-11-15T10:01:00Z\"}")));
    assertFailsClosed(() -> client.authorise(BODY));

    authServer.stubFor(get(urlEqualTo(PIN_KEY)).willReturn(aResponse().withStatus(503)));
    assertFailsClosed(() -> client.pinKey());
  }

  @Test
  void aSlowAnswerTimesOutAndFailsClosed() {
    authServer.stubFor(post(urlEqualTo(TXN_AUTH)).willReturn(okJson(AUTHORISED).withFixedDelay(1_000)));

    assertFailsClosed(() -> client.authorise(BODY));
  }

  @Test
  void theBreakerOpensAfterFailuresAndThenCallsNothing() {
    authServer.stubFor(post(urlEqualTo(TXN_AUTH)).willReturn(aResponse().withStatus(500)));
    for (int i = 0; i < 4; i++) {
      assertThatThrownBy(() -> client.authorise(BODY)).isInstanceOf(MwException.class);
    }
    authServer.resetRequests();

    assertFailsClosed(() -> client.authorise(BODY));
    authServer.verify(0, postRequestedFor(urlEqualTo(TXN_AUTH)));
  }

  @Test
  void withoutTheClientCertificateTheHandshakeFailsClosed() {
    authServer.stubFor(post(urlEqualTo(TXN_AUTH)).willReturn(okJson(AUTHORISED)));
    AuthClient noCert = client(provider(null));

    assertFailsClosed(() -> noCert.authorise(BODY));
    authServer.verify(0, postRequestedFor(urlEqualTo(TXN_AUTH)));
  }
}
