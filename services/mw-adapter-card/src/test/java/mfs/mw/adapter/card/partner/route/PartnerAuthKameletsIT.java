package mfs.mw.adapter.card.partner.route;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import mfs.mw.adapter.card.TestInfrastructure;
import mfs.mw.adapter.card.dto.ConfigChangedEvent;
import mfs.mw.adapter.card.partner.FakeVaultFunction;
import mfs.mw.adapter.card.partner.PartnerCall;
import mfs.mw.adapter.card.partner.PartnerResponse;
import mfs.mw.adapter.card.partner.TestKeystores;
import mfs.mw.adapter.card.partner.config.PartnerConfigStore;
import mfs.mw.adapter.card.partner.config.PartnerProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The auth options of ticket 87 through the whole partner engine (record, route loader,
 * {@code mw-http-call}, the auth Kamelet, the partner http component and host guard) against
 * WireMock partners: basic, OAuth2 client credentials (one token for many calls, one refresh after
 * a partner 401), a detached JWS over the body, and mTLS with a named client keystore from
 * Vault at startup (fails closed with the wrong one). Secrets come from the fake vault function
 * (identical in the three adapters).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestInfrastructure.class)
@DirtiesContext
class PartnerAuthKameletsIT {

  static final String SERVICE = "mw-adapter-card";
  static final WireMockServer PARTNER = new WireMockServer(WireMockConfiguration.options().dynamicPort());
  static final WireMockServer TLS_PARTNER = new WireMockServer(WireMockConfiguration.options().httpDisabled(true)
      .dynamicHttpsPort().keystorePath(TestKeystores.store("server")).keystorePassword(TestKeystores.PASSWORD)
      .keyManagerPassword(TestKeystores.PASSWORD).keystoreType("PKCS12").needClientAuth(true)
      .trustStorePath(TestKeystores.store("server-trust")).trustStorePassword(TestKeystores.PASSWORD).trustStoreType("PKCS12"));

  static {
    PARTNER.start();
    TLS_PARTNER.start();
    for (String context : List.of("good", "wrong")) {
      secret("tlsco", "mtls-" + context + "-keystore", TestKeystores.base64(context));
      secret("tlsco", "mtls-" + context + "-keystore-password", TestKeystores.PASSWORD);
      secret("tlsco", "mtls-" + context + "-truststore", TestKeystores.base64("client-trust"));
      secret("tlsco", "mtls-" + context + "-truststore-password", TestKeystores.PASSWORD);
    }
  }

  static void secret(String partner, String field, String value) {
    FakeVaultFunction.VALUES.put("secret:" + SERVICE + "/" + partner + "#" + field, value);
  }

  @DynamicPropertySource
  static void tlsContexts(DynamicPropertyRegistry registry) {
    registry.add("mw.partners.tls.contexts.mtls-good.partner-id", () -> "tlsco");
    registry.add("mw.partners.tls.contexts.mtls-wrong.partner-id", () -> "tlsco");
    registry.add("mw.partners.tls.contexts.mtls-good.truststore", () -> "true");
    registry.add("mw.partners.tls.contexts.mtls-wrong.truststore", () -> "true");
  }

  @AfterAll
  static void stop() {
    PARTNER.stop();
    TLS_PARTNER.stop();
  }

  private final PartnerConfigStore store;
  private final PartnerGateway gateway;
  private final String family;

  @Autowired
  PartnerAuthKameletsIT(PartnerConfigStore store, PartnerGateway gateway, PartnerProperties properties) {
    this.store = store;
    this.gateway = gateway;
    this.family = properties.family();
  }

  @BeforeEach
  void reset() {
    PARTNER.resetAll();
    TLS_PARTNER.resetAll();
  }

  void publish(String partner, long version, String url, String httpCallParams) {
    String route = """
        - route:
            from:
              uri: direct:partner-%1$s
              steps:
                - to: "kamelet:mw-map?mapping=PAY.request"
                - to: "kamelet:mw-http-call?url=%2$s&method=POST&path=/%1$s/pay&%3$s"
                - to: "kamelet:mw-classify?mapping=PAY.response&definiteStatuses=422"
                - to: "kamelet:mw-audit"
        """.formatted(partner, url, httpCallParams);
    Map<String, Object> value = Map.of(
        "family", family,
        "allowedHosts", List.of("localhost"),
        "timeoutMs", 3000,
        "route", route,
        "mappings", Map.of(
            "PAY.request", "{\"ref\": reference}",
            "PAY.response", "status = 200 ? {\"outcome\": \"SUCCESS\", \"partnerRef\": body.receipt}"
                + " : {\"outcome\": \"UNKNOWN\", \"reason\": \"UNEXPECTED_STATUS\"}"));
    store.apply(new ConfigChangedEvent(
        1, "partner/" + partner, ConfigChangedEvent.ConfigType.PARTNER, ConfigChangedEvent.Change.UPSERT, version, value,
        Instant.parse("2026-11-18T10:00:00Z"), "ops-maker01", "ops-checker01", "corr-87"));
    assertThat(store.instance(partner)).as("record of %s loaded", partner).isPresent();
    assertThat(store.instance(partner).orElseThrow().version()).isEqualTo(version);
  }

  /** The next version of {@code partner} (the mTLS partner tlsco is shared by the REST and SOAP tests). */
  long nextVersion(String partner) {
    return store.instance(partner).map(r -> r.version() + 1).orElse(1L);
  }

  static PartnerCall pay(String partner, String reference) {
    return new PartnerCall(partner, "PAY", "BILL_PAY", "01933b8e-7c1a-7b2e-9f3d-000000000087", "corr-87", reference,
        Map.of("creditParty", Map.of("type", "BILLER_ACCOUNT", "id", "77001234")));
  }

  static void receipt(WireMockServer server, com.github.tomakehurst.wiremock.client.MappingBuilder request) {
    server.stubFor(request.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
        .withBody("{\"receipt\":\"R-87\"}")));
  }

  String local() {
    return "http://localhost:" + PARTNER.port();
  }

  @Test
  void basicSendsTheUserAndPasswordFromTheSecret() {
    secret("basicco", "user", "fake-basic-user");
    secret("basicco", "password", "fake-basic-password");
    publish("basicco", 1, local(), "auth=basic&userField=user&secretField=password");
    receipt(PARTNER, post(urlEqualTo("/basicco/pay")).withHeader("Authorization", equalTo("Basic "
        + Base64.getEncoder().encodeToString("fake-basic-user:fake-basic-password".getBytes(StandardCharsets.UTF_8)))));

    assertThat(gateway.call(pay("basicco", "BITS-87-B"))).isInstanceOf(PartnerResponse.Success.class);
  }

  @Test
  void oauth2FetchesOneTokenForManyPayments() {
    secret("oauthco", "client-secret", "fake-oauth-client-secret");
    PARTNER.stubFor(post(urlEqualTo("/oauthco/token")).willReturn(aResponse().withStatus(200)
        .withHeader("Content-Type", "application/json")
        .withBody("{\"access_token\":\"fake-oauth-token-1\",\"token_type\":\"bearer\",\"expires_in\":3600}")));
    receipt(PARTNER, post(urlEqualTo("/oauthco/pay")).withHeader("Authorization", equalTo("Bearer fake-oauth-token-1")));
    publish("oauthco", 1, local(), "auth=oauth2-cc&secretField=client-secret&clientId=bits-mw&scope=pay&tokenUrl="
        + local() + "/oauthco/token");

    for (int i = 0; i < 5; i++) {
      assertThat(gateway.call(pay("oauthco", "BITS-87-O" + i))).isInstanceOf(PartnerResponse.Success.class);
    }

    PARTNER.verify(1, postRequestedFor(urlEqualTo("/oauthco/token")));
    PARTNER.verify(5, postRequestedFor(urlEqualTo("/oauthco/pay")));
  }

  /** A partner 401 drops the token and sends once more with a fresh one; a second 401 is not retried. */
  @Test
  void oauth2RefreshesTheTokenOnceAfterAPartner401() {
    secret("refreshco", "client-secret", "fake-refresh-client-secret");
    PARTNER.stubFor(post(urlEqualTo("/refreshco/token")).inScenario("tokens").whenScenarioStateIs(Scenario.STARTED)
        .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
            .withBody("{\"access_token\":\"fake-stale-token\",\"expires_in\":3600}"))
        .willSetStateTo("second"));
    PARTNER.stubFor(post(urlEqualTo("/refreshco/token")).inScenario("tokens").whenScenarioStateIs("second")
        .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
            .withBody("{\"access_token\":\"fake-fresh-token\",\"expires_in\":3600}")));
    PARTNER.stubFor(post(urlEqualTo("/refreshco/pay")).withHeader("Authorization", equalTo("Bearer fake-stale-token"))
        .willReturn(aResponse().withStatus(401)));
    receipt(PARTNER, post(urlEqualTo("/refreshco/pay")).withHeader("Authorization", equalTo("Bearer fake-fresh-token")));
    publish("refreshco", 1, local(), "auth=oauth2-cc&secretField=client-secret&clientId=bits-mw&tokenUrl="
        + local() + "/refreshco/token");

    assertThat(gateway.call(pay("refreshco", "BITS-87-R1"))).isInstanceOf(PartnerResponse.Success.class);
    assertThat(gateway.call(pay("refreshco", "BITS-87-R2"))).isInstanceOf(PartnerResponse.Success.class);

    PARTNER.verify(2, postRequestedFor(urlEqualTo("/refreshco/token")));
    PARTNER.verify(1, postRequestedFor(urlEqualTo("/refreshco/pay")).withHeader("Authorization", equalTo("Bearer fake-stale-token")));
    PARTNER.verify(2, postRequestedFor(urlEqualTo("/refreshco/pay")).withHeader("Authorization", equalTo("Bearer fake-fresh-token")));
  }

  @Test
  void oauth2SendsAtMostOnceMoreWhenThePartnerKeepsAnswering401() {
    secret("denyco", "client-secret", "fake-deny-client-secret");
    PARTNER.stubFor(post(urlEqualTo("/denyco/token")).willReturn(aResponse().withStatus(200)
        .withHeader("Content-Type", "application/json").withBody("{\"access_token\":\"fake-deny-token\",\"expires_in\":3600}")));
    PARTNER.stubFor(post(urlEqualTo("/denyco/pay")).willReturn(aResponse().withStatus(401)));
    publish("denyco", 1, local(), "auth=oauth2-cc&secretField=client-secret&clientId=bits-mw&tokenUrl=" + local() + "/denyco/token");

    assertThat(gateway.call(pay("denyco", "BITS-87-D"))).isNotInstanceOf(PartnerResponse.Success.class);

    PARTNER.verify(2, postRequestedFor(urlEqualTo("/denyco/pay")));
    PARTNER.verify(2, postRequestedFor(urlEqualTo("/denyco/token")));
  }

  @Test
  void jwsSignsTheSentBodyAsADetachedJwsInTheNamedHeader() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    KeyPair keys = generator.generateKeyPair();
    secret("jwsco", "jws-key", "-----BEGIN PRIVATE KEY-----\n"
        + Base64.getMimeEncoder().encodeToString(keys.getPrivate().getEncoded()) + "\n-----END PRIVATE KEY-----\n");
    receipt(PARTNER, post(urlEqualTo("/jwsco/pay")).withHeader("X-Jws", matching("^[A-Za-z0-9_-]+\\.\\.[A-Za-z0-9_-]+$")));
    publish("jwsco", 1, local(), "auth=jws&secretField=jws-key&alg=RS256&keyId=bits-2026&authHeader=X-Jws");

    assertThat(gateway.call(pay("jwsco", "BITS-87-J"))).isInstanceOf(PartnerResponse.Success.class);

    LoggedRequest sent = PARTNER.findAll(postRequestedFor(urlEqualTo("/jwsco/pay"))).getFirst();
    JWSObject jws = JWSObject.parse(sent.getHeader("X-Jws"), new Payload(sent.getBodyAsString()));
    assertThat(jws.getHeader().getKeyID()).isEqualTo("bits-2026");
    assertThat(jws.verify(new RSASSAVerifier((RSAPublicKey) keys.getPublic()))).isTrue();
  }

  /** The client keystore named by the route, read from Vault at startup; the wrong one fails closed. */
  @Test
  void mtlsSucceedsWithTheNamedKeystoreAndFailsClosedWithAWrongOne() {
    String url = "https://localhost:" + TLS_PARTNER.httpsPort();
    receipt(TLS_PARTNER, post(urlEqualTo("/tlsco/pay")));

    long v = nextVersion("tlsco");
    publish("tlsco", v, url, "sslContextRef=mtls-good");
    assertThat(gateway.call(pay("tlsco", "BITS-87-T1"))).isInstanceOf(PartnerResponse.Success.class);

    publish("tlsco", v + 1, url, "sslContextRef=mtls-wrong");
    assertThat(gateway.call(pay("tlsco", "BITS-87-T2")))
        .isInstanceOfAny(PartnerResponse.Unknown.class, PartnerResponse.NotSent.class);

    TLS_PARTNER.verify(1, postRequestedFor(urlEqualTo("/tlsco/pay")));
  }

  // --- review of 85: a SOAP partner on Basic or mTLS, onboarded by config ---------------------

  void publishSoap(String partner, long version, String address, String soapCallParams) {
    String route = """
        - route:
            from:
              uri: direct:partner-%1$s
              steps:
                - to: "kamelet:mw-map?mapping=PAY.request"
                - to: "kamelet:mw-soap-call?address=%2$s/%1$s/ws&request=json&response=json&namespace=urn:biller:v1&soapAction=urn:biller:v1:Pay&%3$s"
                - to: "kamelet:mw-classify?mapping=PAY.response&definiteStatuses=200"
                - to: "kamelet:mw-audit"
        """.formatted(partner, address, soapCallParams);
    Map<String, Object> value = Map.of(
        "family", family,
        "allowedHosts", List.of("localhost"),
        "timeoutMs", 3000,
        "route", route,
        "mappings", Map.of(
            "PAY.request", "{\"Pay\": {\"ref\": reference}}",
            "PAY.response", "body.PayResponse.receipt ? {\"outcome\": \"SUCCESS\", \"partnerRef\": body.PayResponse.receipt}"
                + " : {\"outcome\": \"UNKNOWN\", \"reason\": \"MALFORMED_REPLY\"}"));
    store.apply(new ConfigChangedEvent(
        1, "partner/" + partner, ConfigChangedEvent.ConfigType.PARTNER, ConfigChangedEvent.Change.UPSERT, version, value,
        Instant.parse("2026-11-18T10:00:00Z"), "ops-maker01", "ops-checker01", "corr-85"));
    assertThat(store.instance(partner)).as("record of %s loaded", partner).isPresent();
    assertThat(store.instance(partner).orElseThrow().version()).isEqualTo(version);
  }

  static void soapReceipt(WireMockServer server, com.github.tomakehurst.wiremock.client.MappingBuilder request) {
    server.stubFor(request.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/xml; charset=UTF-8")
        .withBody("<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body>"
            + "<b:PayResponse xmlns:b=\"urn:biller:v1\"><b:receipt>R-85</b:receipt></b:PayResponse>"
            + "</soap:Body></soap:Envelope>")));
  }

  @Test
  void aSoapPartnerOnBasicGetsTheUserAndPasswordFromTheSecret() {
    secret("soapbasic", "user", "fake-soap-user");
    secret("soapbasic", "password", "fake-soap-password");
    publishSoap("soapbasic", 1, local(), "auth=basic&userField=user&secretField=password");
    soapReceipt(PARTNER, post(urlEqualTo("/soapbasic/ws"))
        .withHeader("Authorization", equalTo("Basic "
            + Base64.getEncoder().encodeToString("fake-soap-user:fake-soap-password".getBytes(StandardCharsets.UTF_8))))
        .withRequestBody(matching("(?s).*<Pay xmlns=\"urn:biller:v1\"><ref>BITS-85-B</ref></Pay>.*")));

    assertThat(gateway.call(pay("soapbasic", "BITS-85-B"))).isInstanceOf(PartnerResponse.Success.class);
  }

  @Test
  void aSoapPartnerOnMtlsUsesTheNamedKeystoreAndFailsClosedWithAWrongOne() {
    String address = "https://localhost:" + TLS_PARTNER.httpsPort();
    soapReceipt(TLS_PARTNER, post(urlEqualTo("/tlsco/ws")));

    long v = nextVersion("tlsco");
    publishSoap("tlsco", v, address, "sslContextRef=mtls-good");
    assertThat(gateway.call(pay("tlsco", "BITS-85-T1"))).isInstanceOf(PartnerResponse.Success.class);

    publishSoap("tlsco", v + 1, address, "sslContextRef=mtls-wrong");
    assertThat(gateway.call(pay("tlsco", "BITS-85-T2")))
        .isInstanceOfAny(PartnerResponse.Unknown.class, PartnerResponse.NotSent.class);

    TLS_PARTNER.verify(1, postRequestedFor(urlEqualTo("/tlsco/ws")));
  }

  // --- no automatic resend by the HTTP client (fresh-stack API suite, topup tsq) ------------------

  /**
   * A partner 503 to a PAY reaches the partner once: Apache HttpClient's default retry strategy
   * would send the POST again by itself (after Retry-After or 1 s), outside the breaker, the audit
   * and the no-redelivery rule of a PAY, and a partner that applies the second one turns a txn
   * expected FAILED into COMPLETED.
   */
  @Test
  void aPay503ReachesThePartnerExactlyOnce() {
    PARTNER.stubFor(post(urlEqualTo("/busyco/pay")).willReturn(aResponse().withStatus(503).withHeader("Retry-After", "1")));
    publish("busyco", 1, local(), "auth=none");

    assertThat(gateway.call(pay("busyco", "BITS-503-P"))).isInstanceOf(PartnerResponse.Unknown.class);

    PARTNER.verify(1, postRequestedFor(urlEqualTo("/busyco/pay")));
  }

  @Test
  void aPay503OverMtlsReachesThePartnerExactlyOnce() {
    TLS_PARTNER.stubFor(post(urlEqualTo("/tlsco/pay")).willReturn(aResponse().withStatus(503).withHeader("Retry-After", "1")));
    publish("tlsco", nextVersion("tlsco"), "https://localhost:" + TLS_PARTNER.httpsPort(), "sslContextRef=mtls-good");

    assertThat(gateway.call(pay("tlsco", "BITS-503-T"))).isInstanceOf(PartnerResponse.Unknown.class);

    TLS_PARTNER.verify(1, postRequestedFor(urlEqualTo("/tlsco/pay")));
  }

  @Test
  void aTokenEndpoint503IsAskedExactlyOnceAndThePayIsNotSent() {
    secret("busytoken", "client-secret", "fake-busy-client-secret");
    PARTNER.stubFor(post(urlEqualTo("/busytoken/token")).willReturn(aResponse().withStatus(503).withHeader("Retry-After", "1")));
    receipt(PARTNER, post(urlEqualTo("/busytoken/pay")));
    publish("busytoken", 1, local(), "auth=oauth2-cc&secretField=client-secret&clientId=bits-mw&tokenUrl="
        + local() + "/busytoken/token");

    assertThat(gateway.call(pay("busytoken", "BITS-503-O"))).isInstanceOf(PartnerResponse.NotSent.class);

    PARTNER.verify(1, postRequestedFor(urlEqualTo("/busytoken/token")));
    PARTNER.verify(0, postRequestedFor(urlEqualTo("/busytoken/pay")));
  }

  @Test
  void aSoapPay503ReachesThePartnerExactlyOnce() {
    PARTNER.stubFor(post(urlEqualTo("/soapbusy/ws")).willReturn(aResponse().withStatus(503).withHeader("Retry-After", "1")));
    publishSoap("soapbusy", 1, local(), "auth=none");

    assertThat(gateway.call(pay("soapbusy", "BITS-503-S"))).isInstanceOf(PartnerResponse.Unknown.class);

    PARTNER.verify(1, postRequestedFor(urlEqualTo("/soapbusy/ws")));
  }
}
