package mfs.mw.adapter.atm.partner.kamelet;

import static org.assertj.core.api.Assertions.assertThat;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import mfs.mw.adapter.atm.partner.PartnerTestSeeds;
import mfs.mw.adapter.atm.partner.auth.OAuth2ClientCredentials;
import mfs.mw.adapter.atm.partner.config.PartnerConfig;
import mfs.mw.adapter.atm.partner.config.PartnerEndpointPolicy;
import mfs.mw.adapter.atm.partner.config.PartnerProperties;
import mfs.mw.adapter.atm.partner.route.PartnerDnsResolver;
import mfs.mw.adapter.atm.partner.route.PartnerHeaderFilterStrategy;
import mfs.mw.adapter.atm.partner.route.PartnerHostGuard;
import mfs.mw.adapter.atm.partner.route.PartnerHostRefusedException;
import mfs.mw.adapter.atm.partner.route.PartnerHttpComponent;
import mfs.mw.adapter.atm.partner.route.PartnerExchange;
import mfs.mw.adapter.atm.partner.route.PartnerRuntime;
import org.apache.camel.Exchange;
import org.apache.camel.builder.AdviceWith;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.mock.MockEndpoint;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The auth Kamelets of ticket 87 alone, each on a plain Camel context: a test route calls the
 * Kamelet exactly as {@code mw-http-call} does (every auth parameter passed), {@code AdviceWith}
 * adds a {@code MockEndpoint} after it, and the headers it set are checked against known vectors.
 */
class AuthKameletsTest {

  static final JsonMapper JSON = JsonMapper.builder().build();
  static final DefaultCamelContext CAMEL = new DefaultCamelContext();
  static final WireMockServer TOKENS = new WireMockServer(WireMockConfiguration.options().dynamicPort());
  static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-18T10:00:00Z"), ZoneOffset.UTC);

  /** How mw-http-call calls an auth Kamelet: every parameter, empty when the route sets none. */
  static String authUri(String auth, Map<String, String> params) {
    StringBuilder uri = new StringBuilder("kamelet:mw-auth-" + auth + "?");
    for (String p : List.of("secretField", "header", "keyId", "userField", "tokenUrl", "clientId", "scope", "audience", "alg")) {
      uri.append(p).append('=').append(params.getOrDefault(p, "")).append('&');
    }
    return uri.substring(0, uri.length() - 1);
  }

  @BeforeAll
  static void camel() throws Exception {
    TOKENS.start();
    PartnerEndpointPolicy policy = new PartnerEndpointPolicy(new PartnerProperties.Security(Map.of(), List.of("localhost")));
    CAMEL.getRegistry().bind("http", new PartnerHttpComponent(new PartnerDnsResolver(policy)));
    CAMEL.getRegistry().bind("mwPartnerHeaderFilter", new PartnerHeaderFilterStrategy());
    CAMEL.addRoutes(new PartnerHostGuard(policy, PartnerTestSeeds.noTlsContexts()));
    CAMEL.getRegistry().bind("mwAuth", new MwAuthStep(CLOCK, new OAuth2ClientCredentials(CAMEL.createProducerTemplate(),
        JSON, CLOCK, Duration.ofSeconds(30))));
    CAMEL.addRoutes(new RouteBuilder() {
      @Override
      public void configure() {
        from("direct:basic").routeId("basic")
            .to(authUri("basic", Map.of("secretField", "password", "userField", "user")));
        from("direct:jws-rs256").routeId("jws-rs256")
            .to(authUri("jws", Map.of("secretField", "jws-key", "alg", "RS256", "keyId", "bits-1")));
        from("direct:jws-es256").routeId("jws-es256")
            .to(authUri("jws", Map.of("secretField", "jws-key", "alg", "ES256", "header", "X-Signature")));
        from("direct:oauth2").routeId("oauth2")
            .to(authUri("oauth2-cc", Map.of("secretField", "client-secret", "tokenUrl", "http://localhost:" + TOKENS.port() + "/oauth/token",
                "clientId", "bits-mw", "scope", "payments.write", "audience", "https://api.acme.test")));
        from("direct:oauth2-foreign").routeId("oauth2-foreign")
            .to(authUri("oauth2-cc", Map.of("secretField", "client-secret", "tokenUrl", "http://127.0.0.2:" + TOKENS.port() + "/oauth/token",
                "clientId", "bits-mw")));
      }
    });
    CAMEL.start();
    for (String route : List.of("basic", "jws-rs256", "jws-es256", "oauth2", "oauth2-foreign")) {
      AdviceWith.adviceWith(CAMEL, route, a -> a.weaveAddLast().to("mock:" + route));
    }
  }

  @AfterAll
  static void stop() {
    CAMEL.stop();
    TOKENS.stop();
  }

  static PartnerRuntime runtime(Map<String, String> secrets) {
    PartnerConfig config = new PartnerConfig(
        true, "- route: {}", Map.of(), List.of("localhost"), "bank", null, null, null, 1000L, 3000L, 2000L,
        null, null, null, null, null);
    return new PartnerRuntime("acme", 1, config, "partner-acme-v1", "direct:partner-acme-v1", Map.of(), secrets,
        List.of("localhost"), Set.of());
  }

  static Exchange call(PartnerRuntime runtime, String body) {
    Exchange exchange = new DefaultExchange(CAMEL);
    exchange.setProperty(PartnerExchange.RUNTIME, runtime);
    exchange.setProperty(PartnerExchange.HTTP_METHOD, "POST");
    exchange.setProperty(PartnerExchange.HTTP_PATH_AND_QUERY, "/v1/payments");
    if (body != null) {
      exchange.setProperty(PartnerExchange.REQUEST_BODY, body);
    }
    return exchange;
  }

  /** RFC 7617 section 2: user "Aladdin", password "open sesame". */
  @Test
  void basicSetsTheAuthorizationHeaderOfRfc7617() throws Exception {
    MockEndpoint mock = CAMEL.getEndpoint("mock:basic", MockEndpoint.class);
    mock.reset();
    mock.expectedMessageCount(1);
    mock.expectedHeaderReceived("Authorization", "Basic QWxhZGRpbjpvcGVuIHNlc2FtZQ==");

    CAMEL.createProducerTemplate().send("direct:basic",
        call(runtime(Map.of("user", "Aladdin", "password", "open sesame")), "{}"));

    mock.assertIsSatisfied();
  }

  static String pem(KeyPair keys) {
    return "-----BEGIN PRIVATE KEY-----\n"
        + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(keys.getPrivate().getEncoded())
        + "\n-----END PRIVATE KEY-----\n";
  }

  static KeyPair keys(String algorithm, java.security.spec.AlgorithmParameterSpec spec) throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance(algorithm);
    generator.initialize(spec);
    return generator.generateKeyPair();
  }

  static Exchange signed(String route, KeyPair keys, String body) throws Exception {
    MockEndpoint mock = CAMEL.getEndpoint("mock:" + route, MockEndpoint.class);
    mock.reset();
    mock.expectedMessageCount(1);
    CAMEL.createProducerTemplate().send("direct:" + route, call(runtime(Map.of("jws-key", pem(keys))), body));
    mock.assertIsSatisfied();
    return mock.getReceivedExchanges().getFirst();
  }

  /**
   * RS256 is deterministic: the detached JWS (RFC 7515 appendix F) must be byte for byte the one
   * nimbus-jose-jwt (an independent implementation) produces for the same header, key and body.
   */
  @Test
  void jwsRs256SignsTheBodyAsADetachedJwsEqualToAnIndependentImplementation() throws Exception {
    KeyPair keys = keys("RSA", new java.security.spec.RSAKeyGenParameterSpec(2048, java.math.BigInteger.valueOf(65537)));
    String body = "{\"ref\":\"BITS0001\",\"amount\":\"412.50\"}";

    String jws = signed("jws-rs256", keys, body).getMessage().getHeader("X-JWS-Signature", String.class);

    String[] parts = jws.split("\\.", -1);
    assertThat(parts).hasSize(3);
    assertThat(parts[1]).as("detached: no payload").isEmpty();
    assertThat(JSON.readTree(Base64.getUrlDecoder().decode(parts[0])))
        .isEqualTo(JSON.readTree("{\"alg\":\"RS256\",\"kid\":\"bits-1\"}"));
    JWSObject parsed = JWSObject.parse(jws, new Payload(body));
    assertThat(parsed.verify(new RSASSAVerifier((RSAPublicKey) keys.getPublic()))).isTrue();
    JWSObject sameHeader = new JWSObject(parsed.getHeader(), new Payload(body));
    sameHeader.sign(new RSASSASigner(keys.getPrivate()));
    assertThat(parts[2]).isEqualTo(sameHeader.getSignature().toString());
  }

  /** ES256 (random k): verified with the public key by nimbus-jose-jwt; a changed body fails. */
  @Test
  void jwsEs256SignsTheBodyInTheNamedHeaderAndVerifiesWithThePublicKey() throws Exception {
    KeyPair keys = keys("EC", new ECGenParameterSpec("secp256r1"));
    String body = "{\"ref\":\"BITS0002\"}";

    Exchange out = signed("jws-es256", keys, body);
    String jws = out.getMessage().getHeader("X-Signature", String.class);

    assertThat(out.getMessage().getHeader("X-JWS-Signature")).isNull();
    assertThat(jws.split("\\.", -1)[1]).isEmpty();
    JWSObject parsed = JWSObject.parse(jws, new Payload(body));
    assertThat(parsed.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.ES256);
    assertThat(parsed.getHeader().getKeyID()).isNull();
    assertThat(parsed.verify(new ECDSAVerifier((ECPublicKey) keys.getPublic()))).isTrue();
    assertThat(JWSObject.parse(jws, new Payload(body + " ")).verify(new ECDSAVerifier((ECPublicKey) keys.getPublic())))
        .isFalse();
  }

  static void tokenAnswers(String token, int expiresIn) {
    TOKENS.resetAll();
    TOKENS.stubFor(post(urlEqualTo("/oauth/token")).willReturn(aResponse().withStatus(200)
        .withHeader("Content-Type", "application/json")
        .withBody("{\"access_token\":\"" + token + "\",\"token_type\":\"Bearer\",\"expires_in\":" + expiresIn + "}")));
  }

  static Exchange authorised(String route, PartnerRuntime runtime) throws Exception {
    MockEndpoint mock = CAMEL.getEndpoint("mock:" + route, MockEndpoint.class);
    mock.reset();
    mock.expectedMessageCount(1);
    CAMEL.createProducerTemplate().send("direct:" + route, call(runtime, "{}"));
    mock.assertIsSatisfied();
    return mock.getReceivedExchanges().getFirst();
  }

  /**
   * RFC 6749 4.4: one client-credentials token request (client authenticated with HTTP Basic, the
   * form body with grant_type, scope and audience), then the token is reused until expires_in minus
   * the skew: two calls, one token request.
   */
  @Test
  void oauth2FetchesOneTokenAndReusesItForTheNextCall() throws Exception {
    tokenAnswers("fake-access-token-1", 3600);
    PartnerRuntime runtime = runtime(Map.of("client-secret", "fake-client-secret"));

    Exchange first = authorised("oauth2", runtime);
    Exchange second = authorised("oauth2", runtime);

    assertThat(first.getMessage().getHeader("Authorization")).isEqualTo("Bearer fake-access-token-1");
    assertThat(second.getMessage().getHeader("Authorization")).isEqualTo("Bearer fake-access-token-1");
    TOKENS.verify(1, postRequestedFor(urlEqualTo("/oauth/token"))
        .withHeader("Authorization", equalTo("Basic " + Base64.getEncoder().encodeToString(
            "bits-mw:fake-client-secret".getBytes(StandardCharsets.UTF_8))))
        .withHeader("Content-Type", containing("application/x-www-form-urlencoded"))
        .withRequestBody(containing("grant_type=client_credentials"))
        .withRequestBody(containing("scope=payments.write"))
        .withRequestBody(containing("audience=https%3A%2F%2Fapi.acme.test")));
  }

  /** A token whose expires_in is inside the skew is never reused: every call gets a fresh one. */
  @Test
  void oauth2FetchesAgainOnceTheTokenIsWithinTheSkewOfItsExpiry() throws Exception {
    tokenAnswers("fake-short-token", 20);
    PartnerRuntime runtime = runtime(Map.of("client-secret", "fake-client-secret-2"));

    authorised("oauth2", runtime);
    authorised("oauth2", runtime);

    TOKENS.verify(2, postRequestedFor(urlEqualTo("/oauth/token")));
  }

  /**
   * Ticket 139: the token cache is keyed per loaded version (a dry-run candidate is never the live
   * version) and per client secret: a candidate never reuses the live partner's token, and a
   * rotated secret never gets the token fetched with the old one.
   */
  @Test
  void aCandidateAndAChangedSecretNeverReuseTheLiveToken() throws Exception {
    tokenAnswers("fake-live-token", 3600);
    PartnerRuntime live = runtime(Map.of("client-secret", "fake-client-secret-139"));
    assertThat(authorised("oauth2", live).getMessage().getHeader("Authorization")).isEqualTo("Bearer fake-live-token");

    tokenAnswers("fake-candidate-token", 3600);
    PartnerRuntime candidate = new PartnerRuntime("acme", 2, live.config(), PartnerRuntime.candidateRouteId("acme", 2),
        PartnerRuntime.candidateEndpoint("acme"), Map.of(), live.secrets(), List.of("localhost"), Set.of());
    assertThat(authorised("oauth2", candidate).getMessage().getHeader("Authorization"))
        .isEqualTo("Bearer fake-candidate-token");
    TOKENS.verify(1, postRequestedFor(urlEqualTo("/oauth/token")));

    tokenAnswers("fake-rotated-token", 3600);
    PartnerRuntime rotated = runtime(Map.of("client-secret", "fake-client-secret-139-rotated"));
    assertThat(authorised("oauth2", rotated).getMessage().getHeader("Authorization")).isEqualTo("Bearer fake-rotated-token");
    TOKENS.verify(1, postRequestedFor(urlEqualTo("/oauth/token")));
    assertThat(authorised("oauth2", live).getMessage().getHeader("Authorization")).isEqualTo("Bearer fake-live-token");
  }

  /** The token endpoint is held to the partner's allowed hosts like every partner call: refused unsent. */
  @Test
  void oauth2NeverCallsATokenUrlOutsideThePartnersAllowedHosts() throws Exception {
    tokenAnswers("fake-access-token-x", 3600);
    Exchange exchange = call(runtime(Map.of("client-secret", "fake-client-secret")), "{}");

    CAMEL.createProducerTemplate().send("direct:oauth2-foreign", exchange);

    assertThat(exchange.getException()).hasRootCauseInstanceOf(PartnerHostRefusedException.class);
    assertThat(exchange.getMessage().getHeader("Authorization")).isNull();
    TOKENS.verify(0, postRequestedFor(urlEqualTo("/oauth/token")));
  }
}
