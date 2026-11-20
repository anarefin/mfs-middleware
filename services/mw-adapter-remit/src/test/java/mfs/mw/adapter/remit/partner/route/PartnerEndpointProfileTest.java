package mfs.mw.adapter.remit.partner.route;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.resilience4j.bulkhead.BulkheadRegistry;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mfs.mw.adapter.remit.contracts.ServiceJson;
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
 * The endpoint profile and the maintenance window of a partner record (ticket 131b): a route's
 * {@code url={{endpoint}}} is filled from {@code endpoints} by the selected {@code endpoint} before
 * any check runs, so the filled URL meets every loader rule (allowed hosts included); no other
 * placeholder is allowed, nor one hidden by YAML escapes; a record whose profile has no URL, or a URL
 * that is not a plain base URL, is refused. The window is in force from start to end. Identical in
 * telco, billpay and bank.
 */
class PartnerEndpointProfileTest {

  static final PartnerProperties.Security SECURITY =
      new PartnerProperties.Security(Map.of(), List.of("localhost", "mw-simulators"));
  static final DefaultCamelContext CAMEL = new DefaultCamelContext();
  static PartnerRouteLoader loader;

  static final String ROUTE = """
      - route:
          from:
            uri: direct:partner-acme
            steps:
              - to: "kamelet:mw-map?mapping=PAY.request"
              - to: "kamelet:mw-http-call?url=%s&method=POST&path=/pay/{reference}"
              - to: "kamelet:mw-classify?mapping=PAY.response&definiteStatuses=422"
              - to: "kamelet:mw-audit"
      """;

  @BeforeAll
  static void camel() throws Exception {
    CAMEL.addRoutes(new PartnerRouteConfiguration());
    CAMEL.start();
    PartnerCredentials credentials = mock(PartnerCredentials.class);
    when(credentials.read(anyString(), anyString())).thenReturn("fake-credential");
    loader = new PartnerRouteLoader(CAMEL, new PartnerEndpointPolicy(SECURITY), new KameletCatalogue(), credentials,
        BulkheadRegistry.ofDefaults(), PartnerTestSeeds.noTlsContexts());
  }

  @AfterAll
  static void stop() {
    CAMEL.stop();
  }

  static PartnerConfig record(String url, Map<String, Object> extra) {
    Map<String, Object> value = new LinkedHashMap<>(Map.of(
        "route", ROUTE.formatted(url),
        "family", "billpay",
        "allowedHosts", List.of("localhost", "mw-simulators"),
        "endpoint", "MOCK",
        "endpoints", Map.of("MOCK", "http://mw-simulators:8080/sim", "REAL", "http://localhost:8089/"),
        "mappings", Map.of("PAY.request", "{\"a\": reference}", "PAY.response", "{\"outcome\": \"UNKNOWN\"}")));
    value.putAll(extra);
    return ServiceJson.MAPPER.convertValue(value, PartnerConfig.class);
  }

  static List<String> refusal(PartnerConfig config) {
    try {
      loader.load("acme", 2, config);
    } catch (PartnerConfigRefusedException e) {
      return e.problems();
    }
    throw new AssertionError("not refused");
  }

  @Test
  void theSelectedProfilesBaseUrlFillsTheRoute() {
    PartnerRouteLoader.Loaded mock = loader.load("acme", 2, record("{{endpoint}}", Map.of()));
    assertThat(mock.route().toString()).contains("url=http://mw-simulators:8080/sim&").doesNotContain("{{");

    PartnerRouteLoader.Loaded real = loader.load("acme", 3, record("{{endpoint}}", Map.of("endpoint", "REAL")));
    assertThat(real.route().toString()).contains("url=http://localhost:8089&").doesNotContain("mw-simulators");
  }

  @Test
  void aDryRunCandidateStillGoesToTheDryRunTarget() {
    PartnerRouteLoader.Loaded candidate = loader.load("acme", 4, record("{{endpoint}}", Map.of()),
        new PartnerRouteLoader.DryRunTarget("http://localhost:9999", null));
    assertThat(candidate.route().toString()).contains("url=http://localhost:9999/sim&");
  }

  @Test
  void aProfileWithoutItsUrlOrNoProfileIsRefused() {
    assertThat(refusal(record("{{endpoint}}", Map.of("endpoints", Map.of("REAL", "http://localhost:8089")))))
        .anySatisfy(p -> assertThat(p).contains("endpoints"));
    Map<String, Object> none = new LinkedHashMap<>();
    none.put("endpoint", null);
    assertThat(refusal(record("{{endpoint}}", none))).anySatisfy(p -> assertThat(p).contains("{{endpoint}}"));
    assertThat(refusal(record("{{endpoint}}", Map.of("endpoint", "STAGING")))).anySatisfy(p -> assertThat(p).contains("endpoint"));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "http://localhost:8089/a?b=c",
      "http://localhost:8089/a#b",
      "http://user@localhost:8089",
      "http://localhost:8089/../x",
      "http://localhost:8089/%2e",
      "ftp://localhost",
      "http://localhost:8089/a\"\n- to: x",
      "http://localhost:8089/{{env:HOME}}",
  })
  void anEndpointUrlThatIsNotAPlainBaseUrlIsASecurityRefusal(String url) {
    assertThatThrownBy(() -> loader.load("acme", 2, record("{{endpoint}}", Map.of("endpoints", Map.of("MOCK", url)))))
        .isInstanceOfSatisfying(PartnerConfigRefusedException.class, e -> assertThat(e.security()).isTrue());
  }

  @Test
  void anEndpointHostOutsideTheAllowedHostsIsRefused() {
    assertThatThrownBy(() -> loader.load("acme", 2,
        record("{{endpoint}}", Map.of("endpoints", Map.of("MOCK", "http://evil.example.test")))))
        .isInstanceOfSatisfying(PartnerConfigRefusedException.class, e -> assertThat(e.security()).isTrue());
  }

  @ParameterizedTest
  @ValueSource(strings = {"{{endpoint.REAL}}", "{{env:HOME}}", "\\x7b\\x7bendpoint}}"})
  void noOtherPlaceholderIsFilledOrAllowed(String url) {
    assertThatThrownBy(() -> loader.load("acme", 2, record(url, Map.of())))
        .isInstanceOf(PartnerConfigRefusedException.class);
  }

  @Test
  void theWindowIsInForceFromStartToEnd() {
    Instant start = Instant.parse("2026-11-20T10:00:00Z");
    Instant end = Instant.parse("2026-11-20T12:00:00Z");
    PartnerConfig c = record("{{endpoint}}", Map.of("maintenanceWindow", Map.of("start", start.toString(), "end", end.toString())));

    assertThat(c.inMaintenance(start.minusSeconds(1))).isFalse();
    assertThat(c.inMaintenance(start)).isTrue();
    assertThat(c.inMaintenance(end.minusSeconds(1))).isTrue();
    assertThat(c.inMaintenance(end)).isFalse();
    assertThat(record("{{endpoint}}", Map.of("maintenanceWindow", Map.of("end", end.toString()))).inMaintenance(start)).isTrue();
    assertThat(record("{{endpoint}}", Map.of("maintenance", true)).inMaintenance(end)).isTrue();
    assertThat(record("{{endpoint}}", Map.of()).inMaintenance(end)).isFalse();
  }

  @Test
  void aWindowEndingBeforeItStartsOrAnOverlongMessageIsRefused() {
    assertThat(record("{{endpoint}}", Map.of("maintenanceWindow",
        Map.of("start", "2026-11-20T12:00:00Z", "end", "2026-11-20T10:00:00Z"))).problems())
        .contains("maintenanceWindow.end must be after start");
    assertThat(record("{{endpoint}}", Map.of("maintenanceWindow", Map.of("messageBn", "x".repeat(501)))).problems())
        .contains("maintenanceWindow messages are 1 to 500 characters");
  }
}
