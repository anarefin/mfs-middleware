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
import mfs.mw.adapter.remit.partner.auth.PartnerCredentials;
import mfs.mw.adapter.remit.partner.config.PartnerConfig;
import mfs.mw.adapter.remit.partner.config.PartnerConfigRefusedException;
import mfs.mw.adapter.remit.partner.config.PartnerEndpointPolicy;
import mfs.mw.adapter.remit.partner.config.PartnerProperties;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The route loader's guardrails (ADR D28, ticket 82), on a plain Camel context: the dev seed
 * route loads; every construct outside the allowlist is refused, before the route is added.
 */
class PartnerRouteLoaderTest {

  static final PartnerProperties.Security SECURITY =
      new PartnerProperties.Security(Map.of(), List.of("localhost", "mw-simulators"));
  static final DefaultCamelContext CAMEL = new DefaultCamelContext();
  static PartnerRouteLoader loader;

  @BeforeAll
  static void camel() throws Exception {
    CAMEL.addRoutes(new PartnerRouteConfiguration());
    CAMEL.start();
    PartnerCredentials credentials = mock(PartnerCredentials.class);
    when(credentials.read(anyString(), anyString())).thenReturn("fake-credential");
    loader = new PartnerRouteLoader(
        CAMEL, new PartnerEndpointPolicy(SECURITY), new KameletCatalogue(), credentials, BulkheadRegistry.ofDefaults(), PartnerTestSeeds.noTlsContexts());
  }

  @AfterAll
  static void stop() {
    CAMEL.stop();
  }

  /** A record with {@code steps} (YAML, indented under {@code steps:}) as its route. */
  static PartnerConfig record(String steps) {
    String route = """
        - route:
            from:
              uri: direct:partner-acme
              steps:
        %s
        """.formatted(steps.indent(8).stripTrailing());
    return new PartnerConfig(
        true, route, Map.of("PAY.request", "{\"a\": reference}", "PAY.response", "{\"outcome\": \"UNKNOWN\"}"), List.of("localhost"), "billpay", null, null, null, 3000L, 1500L, null, null, null, null, null, null);
  }

  static final String GOOD = """
      - to: "kamelet:mw-map?mapping=PAY.request"
      - to: "kamelet:mw-http-call?url=http://localhost:8089&method=POST&path=/pay/{reference}&auth=apikey&secretField=api-key"
      - to: "kamelet:mw-classify?mapping=PAY.response&definiteStatuses=422"
      - to: "kamelet:mw-audit"
      """;

  PartnerRouteLoader.Loaded load(PartnerConfig config) {
    return loader.load("acme", 3, config);
  }

  @Test
  void theDevSeedRouteLoads() {
    Map.Entry<String, PartnerConfig> seed = PartnerTestSeeds.devSeed();

    PartnerRouteLoader.Loaded loaded = loader.load(seed.getKey(), 0, seed.getValue());

    assertThat(loaded.runtime().routeId()).isEqualTo("partner-" + seed.getKey() + "-v0");
    assertThat(loaded.route().getInput().getUri()).isEqualTo("direct:partner-" + seed.getKey() + "-v0");
    assertThat(loaded.route().getRouteConfigurationId()).isEqualTo(PartnerRouteConfiguration.ID);
    assertThat(loaded.runtime().kamelets()).contains("mw-map", "mw-classify", "mw-audit").containsAnyOf("mw-http-call", "mw-soap-call");
    assertThat(loaded.runtime().secrets().keySet())
        .allSatisfy(field -> assertThat(seed.getValue().route())
            .containsAnyOf("secretField=" + field, "wsPasswordSecret=" + field));
  }

  @Test
  void aGoodRouteGetsTheRecordsParametersInjected() {
    PartnerRouteLoader.Loaded loaded = load(record(GOOD));

    assertThat(loaded.route().toString()).contains("partnerId=acme", "timeoutMs=1500", "connectTimeoutMs=3000");
    assertThat(loaded.runtime().secrets()).containsEntry("api-key", "fake-credential");
    assertThat(loaded.runtime().allowedHosts()).containsExactly("localhost");
  }

  @Test
  void aRecordWithoutARouteIsRefused() {
    PartnerConfig routeless = record(GOOD).withRoute(null, Map.of(), List.of("localhost"));

    assertThatThrownBy(() -> load(routeless))
        .isInstanceOfSatisfying(PartnerConfigRefusedException.class, e -> assertThat(e.problems()).contains("a route is required"));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      // raw endpoints and code
      "- to: \"exec:rm\"",
      "- to: \"bean:partnerGateway\"",
      "- to: \"http://localhost:8089/pay\"",
      "- toD: \"kamelet:mw-audit\"",
      "- bean: {ref: partnerGateway}",
      "- process: {ref: partnerGateway}",
      // dynamic or resolution-changing Kamelet calls, and Kamelets outside the library
      "- to: \"kamelet:mw-audit?x=${header.mwReference}\"",
      "- to: \"kamelet:mw-audit?location=file:/tmp/evil\"",
      "- to: \"kamelet:mw-audit?partnerId=billagg\"",
      "- to: \"kamelet:other-kamelet\"",
      // error handling belongs to the loader
      "- onException: {exception: [java.lang.Exception], steps: [{to: \"kamelet:mw-audit\"}]}",
      "- doTry: {steps: [{to: \"kamelet:mw-audit\"}]}",
      // headers that redirect the call or impersonate ours, and other languages
      "- setHeader: {name: CamelHttpUri, constant: \"http://evil\"}",
      "- setHeader: {name: mwPartnerId, constant: other}",
      "- setBody: {groovy: \"1\"}",
      "- setBody: {simple: \"${bean:partnerGateway}\"}",
      "- setBody: {simple: \"${sys.user.home}\"}",
      "- log: \"${body}\"",
  })
  void everyConstructOutsideTheAllowlistIsRefused(String step) {
    assertThatThrownBy(() -> load(record(step + "\n" + GOOD)))
        .isInstanceOf(PartnerConfigRefusedException.class);
  }

  /** Security review: placeholders hidden by YAML escapes, quoting or block scalars are found too. */
  @ParameterizedTest
  @ValueSource(strings = {
      "- setBody: {constant: \"\\x7b\\x7benv:HOME}}\"}",
      "- setBody: {constant: \"\\u007b{sys:user.home}}\"}",
      "- setBody:\n    constant: |\n      a\n      {{env:HOME}}",
      "- setHeader: {name: X-A, simple: \"\\u0024{bean:partnerGateway}\"}",
  })
  void placeholdersHiddenInYamlScalarsAreRefused(String step) {
    assertThatThrownBy(() -> load(record(step + "\n" + GOOD))).isInstanceOf(PartnerConfigRefusedException.class);
  }

  /** Security review: Kamelet parameters are checked after URL-decoding, as Camel reads them. */
  @ParameterizedTest
  @ValueSource(strings = {
      "url=https://localhost%3A8089",
      "url=http://localhost:8089%2F%3Fx%3D1",
      "path=/pay%3Fevil=1",
      "path=/pay%23frag",
      "authHeader=X-Key%26bridgeEndpoint=true",
      "keyId=k%3Fx",
      "path=/pay%2F..%2Fadmin",
  })
  void encodedKameletParametersAreRefused(String param) {
    encodedOrOddParameter(param);
  }

  /** Security review round 2: keys are plain names, once each; no encoding trick reaches the checks. */
  @ParameterizedTest
  @ValueSource(strings = {"partner%49d=billagg", "time-outMs=1", "url=https://localhost&url=https://evil.example",
      "1x=2", "secret.field=x", "auth=none&auth=apikey"})
  void oddOrDuplicateKameletParameterKeysAreRefused(String param) {
    assertThatThrownBy(() -> load(record(GOOD.replace("&auth=apikey", "&" + param + "&auth=apikey"))))
        .isInstanceOf(PartnerConfigRefusedException.class);
  }

  void encodedOrOddParameter(String param) {
    String key = param.substring(0, param.indexOf('='));
    String call = GOOD.replaceFirst(key + "=[^&\"]*", java.util.regex.Matcher.quoteReplacement(param));
    if (!call.contains(param)) {
      call = GOOD.replace("&auth=apikey", "&" + param + "&auth=apikey");
    }
    String route = call;
    assertThatThrownBy(() -> load(record(route))).isInstanceOf(PartnerConfigRefusedException.class);
  }

  @Test
  void propertyPlaceholdersAreRefusedAsASecurityRule() {
    assertThatThrownBy(() -> load(record("- setBody: {constant: \"{{hashicorp:secret:mw-adapter-remit/other#key}}\"}\n" + GOOD)))
        .isInstanceOfSatisfying(PartnerConfigRefusedException.class, e -> assertThat(e.security()).isTrue());
  }

  @Test
  void aHostOutsideTheApprovedHostsIsRefused() {
    assertThatThrownBy(() -> load(record(GOOD.replace("http://localhost:8089", "https://evil.example"))))
        .isInstanceOfSatisfying(PartnerConfigRefusedException.class, e -> {
          assertThat(e.security()).isTrue();
          assertThat(e.getMessage()).contains("allowedHosts");
        });
  }

  @Test
  void aRouteWithoutClassifyAndAuditIsRefused() {
    assertThatThrownBy(() -> load(record(GOOD.replace("- to: \"kamelet:mw-audit\"\n", ""))))
        .isInstanceOf(PartnerConfigRefusedException.class)
        .hasMessageContaining("mw-classify then mw-audit");
    assertThatThrownBy(() -> load(record(GOOD.replaceAll("- to: \"kamelet:mw-classify[^\\n]*\\n", ""))))
        .isInstanceOf(PartnerConfigRefusedException.class)
        .hasMessageContaining("mw-classify then mw-audit");
  }

  @Test
  void anythingBesideTheOneRouteIsRefused() {
    PartnerConfig beans = record(GOOD);
    String withBeans = "- beans:\n    - name: x\n      type: java.lang.Object\n" + beans.route();
    assertThatThrownBy(() -> load(beans.withRoute(withBeans, beans.mappings(), beans.allowedHosts())))
        .isInstanceOfSatisfying(PartnerConfigRefusedException.class, e -> assertThat(e.security()).isTrue());
    String withId = beans.route().replace("- route:\n", "- route:\n    id: mine\n");
    assertThatThrownBy(() -> load(beans.withRoute(withId, beans.mappings(), beans.allowedHosts())))
        .isInstanceOf(PartnerConfigRefusedException.class);
  }

  @Test
  void aRouteNamingAMissingMappingOrFromAnotherPartnerIsRefused() {
    assertThatThrownBy(() -> load(record(GOOD.replace("mapping=PAY.request", "mapping=NOPE"))))
        .hasMessageContaining("mapping NOPE");
    PartnerConfig other = record(GOOD);
    String from = other.route().replace("direct:partner-acme", "direct:partner-billagg");
    assertThatThrownBy(() -> load(other.withRoute(from, other.mappings(), other.allowedHosts())))
        .hasMessageContaining("direct:partner-acme");
  }

  @Test
  void aCandidateRunsOnItsOwnEndpointAndBreakerAndCallsTheDryRunTargetInstead() {
    PartnerRouteLoader.Loaded candidate = loader.load(
        "acme", 3, record(GOOD.replace("http://localhost:8089", "http://localhost:8089/api")),
        new PartnerRouteLoader.DryRunTarget("http://mw-simulators:8080/sim/partners/acme", "mw-simulators"));

    // a namespace no partner id can produce (ids are [a-z0-9-], routes partner-<id>-v<n>)
    assertThat(candidate.runtime().routeId()).isEqualTo("partner-acme~candidate~v3");
    assertThat(candidate.runtime().endpoint()).isEqualTo("direct:partner-acme~candidate");
    assertThat(candidate.route().getInput().getUri()).isEqualTo("direct:partner-acme~candidate");
    String route = candidate.route().toString();
    assertThat(route).contains("url=http://mw-simulators:8080/sim/partners/acme/api", "partnerId=acme~candidate")
        .doesNotContain("localhost:8089");
    assertThat(candidate.runtime().allowedHosts()).containsExactly("localhost", "mw-simulators");
  }

  @Test
  void aCandidateTargetOutsideTheAllowedHostsIsRefused() {
    assertThatThrownBy(() -> loader.load("acme", 3, record(GOOD),
            new PartnerRouteLoader.DryRunTarget("https://sandbox.evil.example", null)))
        .isInstanceOfSatisfying(PartnerConfigRefusedException.class, e -> assertThat(e.getMessage()).contains("allowedHosts"));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "http://mw-simulators:8080/sim?x=1",
      "http://mw-simulators:8080/sim#frag",
      "http://mw-simulators:8080/sim%2F..%2Fadmin",
      "http://mw-simulators:8080/sim/{{env:HOME}}",
      "http://mw-simulators:8080/sim/${header.x}",
      "http://user@mw-simulators:8080/sim",
      "http://mw-simulators:8080/sim/../admin",
  })
  void aDryRunTargetWithQueryFragmentEncodingOrPlaceholdersIsRefused(String base) {
    assertThatThrownBy(() -> loader.load("acme", 3, record(GOOD),
            new PartnerRouteLoader.DryRunTarget(base, "mw-simulators")))
        .as(base)
        .isInstanceOfSatisfying(PartnerConfigRefusedException.class, e -> assertThat(e.security()).isTrue());
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "https://sandbox.acme.example/v1?x=1",
      "https://sandbox.acme.example/v1#f",
      "https://u:p@sandbox.acme.example",
      "ftp://sandbox.acme.example",
      "https://sandbox.acme.example/a/../b",
      "sandbox.acme.example",
  })
  void aRecordsDryRunBaseUrlMustBeAPlainAbsoluteHttpUrl(String baseUrl) {
    PartnerConfig good = record(GOOD);
    PartnerConfig bad = new PartnerConfig(
        true, good.route(), good.mappings(), List.of("localhost", "sandbox.acme.example"), "billpay", null, null, new PartnerConfig.DryRun("PAY", Map.of(), baseUrl), 3000L, 1500L, null, null, null, null, null, null);

    assertThat(bad.problems()).as(baseUrl).anySatisfy(p -> assertThat(p).startsWith(PartnerConfig.SECURITY).contains("dryRun.baseUrl"));
  }

  /**
   * A candidate's route id, endpoint and breaker name carry {@code ~}, which no partner id
   * ({@code ^[a-z0-9][a-z0-9-]{0,63}$}) can hold: no partner's live or candidate names reach them,
   * whatever its id ({@code foo-candidate-v2}, {@code foo-candidate}, ...).
   */
  @Test
  void aCandidateNeverCollidesWithAnotherPartnersNames() {
    List<String> ids = List.of("foo", "foo-candidate", "foo-candidate-v2", "foo-v2", "candidate-partner-foo");
    java.util.Set<String> names = new java.util.HashSet<>();
    for (String id : ids) {
      assertThat(new PartnerEndpointPolicy(SECURITY).idProblems(id)).as(id).isEmpty();
      for (long v : new long[] {1, 2}) {
        assertThat(names.add(PartnerRuntime.routeId(id, v))).as(id).isTrue();
        assertThat(names.add(PartnerRuntime.endpoint(id, v))).as(id).isTrue();
        assertThat(names.add(PartnerRuntime.candidateRouteId(id, v))).as(id).isTrue();
      }
      assertThat(names.add(PartnerRuntime.candidateEndpoint(id))).as(id).isTrue();
      assertThat(names.add("mw-cb-" + PartnerRuntime.candidatePartnerParam(id))).as(id).isTrue();
      assertThat(names.add("mw-cb-" + id)).as(id).isTrue();
      assertThat(PartnerRuntime.candidateRouteId(id, 2)).contains("~");
      assertThat(PartnerRuntime.candidateEndpoint(id)).contains("~");
      assertThat(PartnerRuntime.candidatePartnerParam(id)).contains("~");
    }
  }
}
