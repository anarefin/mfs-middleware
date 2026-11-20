package mfs.mw.adapter.remit.partner.route;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.resilience4j.bulkhead.BulkheadRegistry;
import java.util.List;
import java.util.Map;
import mfs.mw.adapter.remit.partner.PartnerTestSeeds;
import mfs.mw.adapter.remit.partner.TestKeystores;
import mfs.mw.adapter.remit.partner.auth.PartnerCredentials;
import mfs.mw.adapter.remit.partner.auth.PartnerTlsContexts;
import mfs.mw.adapter.remit.partner.config.PartnerConfig;
import mfs.mw.adapter.remit.partner.config.PartnerConfigRefusedException;
import mfs.mw.adapter.remit.partner.config.PartnerEndpointPolicy;
import mfs.mw.adapter.remit.partner.config.PartnerProperties;
import mfs.mw.adapter.remit.partner.config.PartnerTlsProperties;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.jsse.SSLContextParameters;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The route loader's rules for the auth options of ticket 87 ({@code mw-http-call} {@code
 * auth=basic|oauth2-cc|jws}, {@code sslContextRef}) and the host guard's for an mTLS call, on a
 * plain Camel context: what each scheme needs, the token URL held to the record's hosts, a TLS
 * client context only of the partner's own and over https.
 */
class AuthRouteLoaderTest {

  static final PartnerProperties.Security SECURITY =
      new PartnerProperties.Security(Map.of(), List.of("localhost"));
  static final DefaultCamelContext CAMEL = new DefaultCamelContext();
  static PartnerRouteLoader loader;

  @BeforeAll
  static void camel() throws Exception {
    CAMEL.addRoutes(new PartnerRouteConfiguration());
    CAMEL.start();
    PartnerCredentials credentials = mock(PartnerCredentials.class);
    when(credentials.read(anyString(), anyString())).thenAnswer(i -> "fake-" + i.getArgument(1));
    loader = new PartnerRouteLoader(
        CAMEL, new PartnerEndpointPolicy(SECURITY), new KameletCatalogue(), credentials, BulkheadRegistry.ofDefaults(), PartnerTestSeeds.noTlsContexts());
  }

  @AfterAll
  static void stop() {
    CAMEL.stop();
  }

  static PartnerConfig record(String httpCall) {
    String route = """
        - route:
            from:
              uri: direct:partner-acme
              steps:
                - to: "kamelet:mw-map?mapping=PAY.request"
                - to: "kamelet:mw-http-call?method=POST&path=/pay&%s"
                - to: "kamelet:mw-classify?mapping=PAY.response"
                - to: "kamelet:mw-audit"
        """.formatted(httpCall);
    return new PartnerConfig(
        true, route, Map.of("PAY.request", "{\"a\": reference}", "PAY.response", "{\"outcome\": \"UNKNOWN\"}"),
        List.of("localhost"), "bank", null, null, null, 3000L, 1500L, null, null, null, null, null, null);
  }

  static PartnerRouteLoader.Loaded load(String httpCall) {
    return loader.load("acme", 1, record(httpCall));
  }

  static List<String> refusal(String httpCall) {
    try {
      load(httpCall);
    } catch (PartnerConfigRefusedException e) {
      return e.problems();
    }
    throw new AssertionError("not refused: " + httpCall);
  }

  @Test
  void eachNewSchemeLoadsWithWhatItNeedsAndReadsItsSecretsNow() {
    assertThat(load("url=http://localhost:8089&auth=basic&userField=user&secretField=password").runtime().secrets())
        .containsEntry("user", "fake-user").containsEntry("password", "fake-password");
    assertThat(load("url=http://localhost:8089&auth=oauth2-cc&secretField=client-secret&clientId=bits"
        + "&tokenUrl=http://localhost:8090/oauth/token&scope=pay+read&audience=https://api.acme.test").runtime().secrets())
        .containsEntry("client-secret", "fake-client-secret");
    assertThat(load("url=http://localhost:8089&auth=jws&secretField=jws-key&alg=ES256&authHeader=X-Sig").runtime().kamelets())
        .contains("mw-http-call");
  }

  @ParameterizedTest
  @CsvSource(delimiter = '|', value = {
      "url=http://localhost:8089&auth=basic&secretField=password | auth basic needs a userField",
      "url=http://localhost:8089&auth=oauth2-cc&secretField=s&tokenUrl=http://localhost:1/t | auth oauth2-cc needs a clientId",
      "url=http://localhost:8089&auth=oauth2-cc&secretField=s&clientId=c | auth oauth2-cc needs a tokenUrl",
      "url=http://localhost:8089&auth=jws&secretField=k | auth jws needs alg RS256 or ES256",
      "url=http://localhost:8089&auth=jws&secretField=k&alg=HS256 | auth jws needs alg RS256 or ES256",
      "url=http://localhost:8089&auth=oauth2-cc&secretField=s&clientId=c&tokenUrl=https://evil.example/t | allowedHosts",
      "url=http://localhost:8089&auth=oauth2-cc&secretField=s&clientId=c&tokenUrl=http://localhost/t?x=1 | character",
      "url=https://localhost:8443&sslContextRef=other-partners | not a TLS client context of this partner",
  })
  void aSchemeMissingWhatItNeedsOrReachingElsewhereIsRefused(String httpCall, String problem) {
    assertThat(String.join("; ", refusal(httpCall))).contains(problem);
  }

  /**
   * Ticket 139: a dry-run candidate's OAuth2 token request goes to the dry-run target too (the
   * token URL's path under the target, as {@code url}), never to the partner's live token endpoint
   * with the live client secret; a live version keeps its own token URL.
   */
  @Test
  void aCandidatesTokenUrlIsTheDryRunTargetNeverTheLiveTokenEndpoint() {
    String httpCall = "url=http://localhost:8089&auth=oauth2-cc&secretField=client-secret&clientId=bits"
        + "&tokenUrl=http://localhost:8090/oauth/token";
    PartnerRouteLoader.Loaded candidate = loader.load("acme", 2, record(httpCall),
        new PartnerRouteLoader.DryRunTarget("http://localhost:8091/sim/partners/acme", null));

    assertThat(uris(candidate))
        .contains("tokenUrl=http://localhost:8091/sim/partners/acme/oauth/token")
        .doesNotContain("localhost:8090");
    assertThat(uris(load(httpCall))).contains("tokenUrl=http://localhost:8090/oauth/token");
  }

  /** The step URIs of a loaded route, unmasked (its toString masks secret-looking options). */
  static String uris(PartnerRouteLoader.Loaded loaded) {
    return loaded.route().getOutputs().stream()
        .filter(o -> o instanceof org.apache.camel.model.ToDefinition)
        .map(o -> ((org.apache.camel.model.ToDefinition) o).getUri())
        .collect(java.util.stream.Collectors.joining(" "));
  }

  /** Contexts name -> partner, each with the {@code good} client key store, registered in {@link #CAMEL}. */
  static PartnerTlsContexts owning(Map<String, String> contexts) {
    PartnerCredentials credentials = mock(PartnerCredentials.class);
    when(credentials.read(anyString(), anyString())).thenAnswer(i -> {
      String field = i.getArgument(1);
      return field.endsWith("-password") ? TestKeystores.PASSWORD
          : TestKeystores.base64(field.endsWith("-truststore") ? "client-trust" : "good");
    });
    Map<String, PartnerTlsProperties.Context> config = new java.util.HashMap<>();
    contexts.forEach((name, partner) -> config.put(name, new PartnerTlsProperties.Context(partner, true)));
    PartnerTlsContexts tls = new PartnerTlsContexts(new PartnerTlsProperties(config), credentials);
    tls.register(CAMEL);
    return tls;
  }

  /**
   * Security review: a context that expects a trust store whose read fails is never registered
   * (no fall-back to the JVM trust store), so a route naming it is refused; one without a trust
   * store uses the JVM's on purpose.
   */
  @Test
  void aContextWhoseExpectedTrustStoreCannotBeReadIsNotRegisteredAndRefused() {
    PartnerCredentials credentials = mock(PartnerCredentials.class);
    when(credentials.read(anyString(), anyString())).thenAnswer(i -> {
      String field = i.getArgument(1);
      if (field.contains("truststore")) {
        throw new IllegalStateException("no credential " + field + " for partner acme");
      }
      return field.endsWith("-password") ? TestKeystores.PASSWORD : TestKeystores.base64("good");
    });
    PartnerTlsContexts tls = new PartnerTlsContexts(new PartnerTlsProperties(Map.of(
        "acme-pinned", new PartnerTlsProperties.Context("acme", true),
        "acme-jvm-trust", new PartnerTlsProperties.Context("acme", null))), credentials);
    tls.register(CAMEL);

    assertThat(tls.owner("acme-pinned")).isEmpty();
    assertThat(CAMEL.getRegistry().lookupByName("mwTls-acme-pinned")).isNull();
    assertThat(tls.owner("acme-jvm-trust")).contains("acme");
    PartnerRouteLoader withTls = new PartnerRouteLoader(
        CAMEL, new PartnerEndpointPolicy(SECURITY), new KameletCatalogue(), credentials, BulkheadRegistry.ofDefaults(), tls);
    assertThatThrownBy(() -> withTls.load("acme", 1, record("url=https://localhost:8443&sslContextRef=acme-pinned")))
        .isInstanceOfSatisfying(PartnerConfigRefusedException.class,
            e -> assertThat(e.problems()).anySatisfy(p -> assertThat(p).contains("not a TLS client context")));
  }

  @Test
  void aTlsContextOfThePartnerNeedsHttpsAndOneOfAnotherPartnerIsRefused() throws Exception {
    PartnerTlsContexts tls = owning(Map.of("acme-client", "acme", "other-client", "other"));
    PartnerCredentials credentials = mock(PartnerCredentials.class);
    PartnerRouteLoader withTls = new PartnerRouteLoader(
        CAMEL, new PartnerEndpointPolicy(SECURITY), new KameletCatalogue(), credentials, BulkheadRegistry.ofDefaults(), tls);

    assertThat(withTls.load("acme", 1, record("url=https://localhost:8443&sslContextRef=acme-client")).runtime()).isNotNull();
    assertThatThrownBy(() -> withTls.load("acme", 2, record("url=http://localhost:8089&sslContextRef=acme-client")))
        .isInstanceOfSatisfying(PartnerConfigRefusedException.class,
            e -> assertThat(e.problems()).anySatisfy(p -> assertThat(p).contains("https")));
    assertThatThrownBy(() -> withTls.load("acme", 3, record("url=https://localhost:8443&sslContextRef=other-client")))
        .isInstanceOf(PartnerConfigRefusedException.class);

    PartnerHostGuard guard = new PartnerHostGuard(new PartnerEndpointPolicy(SECURITY), tls);
    PartnerRuntime acme = withTls.load("acme", 4, record("url=https://localhost:8443&sslContextRef=acme-client")).runtime();
    String options = "?throwExceptionOnFailure=false&followRedirects=false&headerFilterStrategy=%23mwPartnerHeaderFilter"
        + "&connectTimeout=1&responseTimeout=1&soTimeout=1";
    assertThat(guard.problem(acme, "https://localhost:8443" + options + "&sslContextParameters=%23mwTls-acme-client", null))
        .isNull();
    assertThat(guard.problem(acme, "https://localhost:8443" + options + "&sslContextParameters=%23mwTls-other-client", null))
        .contains("TLS client context");
    assertThat(guard.problem(acme, "http://localhost:8089" + options + "&sslContextParameters=%23mwTls-acme-client", null))
        .contains("https");
    assertThat(guard.problem(acme, "https://localhost:8443" + options + "&sslContextParameters=%23evil", null))
        .contains("TLS client context");
    assertThat(CAMEL.getRegistry().lookupByNameAndType("mwTls-acme-client", SSLContextParameters.class)).isNotNull();
  }
}
