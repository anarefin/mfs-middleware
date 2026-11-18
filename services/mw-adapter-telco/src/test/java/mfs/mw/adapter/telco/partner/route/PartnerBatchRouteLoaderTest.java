package mfs.mw.adapter.telco.partner.route;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.resilience4j.bulkhead.BulkheadRegistry;
import java.util.List;
import java.util.Map;
import mfs.mw.adapter.telco.partner.PartnerTestSeeds;
import mfs.mw.adapter.telco.partner.auth.PartnerCredentials;
import mfs.mw.adapter.telco.partner.config.PartnerConfig;
import mfs.mw.adapter.telco.partner.config.PartnerConfigRefusedException;
import mfs.mw.adapter.telco.partner.config.PartnerEndpointPolicy;
import mfs.mw.adapter.telco.partner.config.PartnerProperties;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Batch partner routes (ticket 86): {@code from: kamelet:mw-sftp-source?...} next to the
 * request/reply shape. The loader injects the partner id and the registry references of the
 * credential and the known hosts (read from OpenBao now, never in the URI), keeps the host to the
 * record's allowed hosts, and refuses every SFTP option outside its short list.
 */
class PartnerBatchRouteLoaderTest {

  static final PartnerProperties.Security SECURITY =
      new PartnerProperties.Security(Map.of("acme", List.of("sftp.acme.test")), List.of("localhost"));
  static final DefaultCamelContext CAMEL = new DefaultCamelContext();
  static PartnerRouteLoader loader;

  static final String SOURCE =
      "kamelet:mw-sftp-source?host=sftp.acme.test&port=2222&username=bits&directory=recon/daily"
          + "&secretField=sftp-password&knownHostsField=sftp-known-hosts&delay=60000";

  @BeforeAll
  static void camel() throws Exception {
    CAMEL.addRoutes(new PartnerRouteConfiguration());
    CAMEL.start();
    PartnerCredentials credentials = mock(PartnerCredentials.class);
    when(credentials.read(anyString(), anyString())).thenReturn("fake-credential");
    when(credentials.read(anyString(), eq("sftp-known-hosts"))).thenReturn("[sftp.acme.test]:2222 ssh-ed25519 AAAAfake");
    loader = new PartnerRouteLoader(
        CAMEL, new PartnerEndpointPolicy(SECURITY), new KameletCatalogue(), credentials, BulkheadRegistry.ofDefaults(), PartnerTestSeeds.noTlsContexts());
  }

  @AfterAll
  static void stop() {
    CAMEL.stop();
  }

  static PartnerConfig batch(String from, String steps) {
    return batch(from, steps, List.of("sftp.acme.test"), null);
  }

  static PartnerConfig batch(String from, String steps, List<String> hosts, PartnerConfig.DryRun dryRun) {
    String route = """
        - route:
            from:
              uri: "%s"
              steps:
        %s
        """.formatted(from, steps.indent(8).stripTrailing());
    return new PartnerConfig(
        true, route, Map.of(), hosts, "billpay", null, null, dryRun, null, null, null, null, null, null, null, null);
  }

  static final String RECON = "- to: \"kamelet:mw-recon?columns=reference,amount,status&delimiter=comma\"";

  PartnerRouteLoader.Loaded load(PartnerConfig config) {
    return loader.load("acme", 3, config);
  }

  @Test
  void aBatchRouteLoadsWithTheSecretsAsRegistryReferences() {
    PartnerRouteLoader.Loaded loaded = load(batch(SOURCE, RECON));

    String from = loaded.route().getInput().getUri();
    assertThat(from).startsWith("kamelet:mw-sftp-source?")
        .contains("partnerId=acme", "password=#bean:mw-sftp-partner-acme-v3-sftp-password",
            "knownHosts=#bean:mw-sftp-partner-acme-v3-sftp-known-hosts")
        .doesNotContain("fake-credential", "privateKey", "secretField", "knownHostsField");
    assertThat(loaded.route().getRouteId()).isEqualTo("partner-acme-v3");
    assertThat(loaded.route().getRouteConfigurationId()).isNull();
    assertThat(loaded.route().toString()).doesNotContain("fake-credential", "AAAAfake");
    assertThat(loaded.beans())
        .containsEntry("mw-sftp-partner-acme-v3-sftp-password", "fake-credential")
        .containsEntry("mw-sftp-partner-acme-v3-sftp-known-hosts", "[sftp.acme.test]:2222 ssh-ed25519 AAAAfake");
    assertThat(loaded.runtime().kamelets()).contains("mw-sftp-source", "mw-recon");
    assertThat(loaded.runtime().batch()).isTrue();
  }

  @Test
  void aKeyLoginIsInjectedAsThePrivateKey() {
    PartnerRouteLoader.Loaded loaded = load(batch(SOURCE.replace("secretField=sftp-password", "auth=key&secretField=sftp-key"), RECON));

    assertThat(loaded.route().getInput().getUri())
        .contains("privateKey=#bean:mw-sftp-partner-acme-v3-sftp-key").doesNotContain("password=");
  }

  @Test
  void aRequestReplyRouteIsNoBatch() {
    PartnerConfig plain = new PartnerConfig(
        true, """
            - route:
                from:
                  uri: direct:partner-acme
                  steps:
                    - to: "kamelet:mw-classify"
                    - to: "kamelet:mw-audit"
            """, Map.of(), List.of(), "billpay", null, null, null, null, null, null, null, null, null, null, null);

    PartnerRouteLoader.Loaded loaded = load(plain);

    assertThat(loaded.runtime().batch()).isFalse();
    assertThat(loaded.beans()).isEmpty();
  }

  @Test
  void theSinkTakesADatedFileName() {
    String steps = """
        - to: "kamelet:mw-sftp-sink?host=sftp.acme.test&username=bits&directory=out&fileName=ack-${date:now:yyyyMMdd}.csv&secretField=sftp-password&knownHostsField=sftp-known-hosts"
        """ + RECON;

    PartnerRouteLoader.Loaded loaded = load(batch(SOURCE, steps));

    assertThat(loaded.runtime().kamelets()).contains("mw-sftp-sink");
    assertThat(loaded.route().toString()).contains("fileName=ack-${date:now:yyyyMMdd}.csv");
  }

  @Test
  void aHostOutsideTheAllowedHostsIsRefused() {
    assertRefused(batch(SOURCE.replace("host=sftp.acme.test", "host=sftp.evil.test"), RECON), true, "allowedHosts");
  }

  @Test
  void anInternalHostIsRefusedUnlessItIsAStaticDevHost() {
    // zeta has no static floor: its record's host must be a public one
    PartnerConfig internal = batch(SOURCE.replace("host=sftp.acme.test", "host=10.1.2.3"), RECON, List.of("10.1.2.3"), null);
    assertThatThrownBy(() -> loader.load("zeta", 1, internal))
        .isInstanceOfSatisfying(PartnerConfigRefusedException.class, e -> {
          assertThat(e.problems()).anySatisfy(p -> assertThat(p).contains("internal address"));
          assertThat(e.security()).isTrue();
        });
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "&delete=true",
      "&strictHostKeyChecking=no",
      "&knownHosts=x",
      "&password=x",
      "&privateKeyFile=/etc/passwd",
      "&auth=none",
      "&port=99999",
      "&maxMessagesPerPoll=0",
  })
  void anOptionOutsideTheListOrAWrongValueIsRefused(String extra) {
    assertRefused(batch(SOURCE + extra, RECON), true, "mw-sftp-source");
  }

  @ParameterizedTest
  @ValueSource(strings = {"directory=../etc", "directory=/root", "username=a$b", "secretField=../x"})
  void anUnsafeValueIsRefused(String value) {
    String key = value.substring(0, value.indexOf('='));
    assertRefused(batch(SOURCE.replaceFirst(key + "=[^&]*", java.util.regex.Matcher.quoteReplacement(value)), RECON), true, "mw-sftp-source");
  }

  @Test
  void aBatchRouteMustEndWithMwRecon() {
    assertRefused(batch(SOURCE, "- log: \"file\""), false, "a batch route must end with mw-recon");
  }

  @Test
  void aBatchRouteHasNoDryRun() {
    assertRefused(batch(SOURCE, RECON, List.of("sftp.acme.test"), new PartnerConfig.DryRun("PAY", Map.of(), null)),
        false, "a batch route has no dry-run");
  }

  @Test
  void theSourceIsOnlyTheStartOfARoute() {
    assertRefused(batch(SOURCE, "- to: \"" + SOURCE + "\"\n" + RECON), true, "mw-sftp-source");
  }

  @Test
  void mwReconColumnsAreNames() {
    assertRefused(batch(SOURCE, "- to: \"kamelet:mw-recon?columns=a'),x('\""), true, "mw-recon");
  }

  @Test
  void aBadSinkFileNameIsRefused() {
    String steps = """
        - to: "kamelet:mw-sftp-sink?host=sftp.acme.test&username=bits&directory=out&fileName=${header.x}.csv&secretField=sftp-password&knownHostsField=sftp-known-hosts"
        """ + RECON;
    assertRefused(batch(SOURCE, steps), true, "mw-sftp-sink");
  }

  // --- review of 86: cron polling, a short delay, a sink that builds its text ------------------

  @Test
  void theSourcePollsOnACronScheduleInsteadOfADelay() {
    String cron = SOURCE.replace("&delay=60000", "&cron=0+0+6+*+*+MON-FRI");

    String from = load(batch(cron, RECON)).route().getInput().getUri();

    assertThat(from).contains("&scheduler=spring&cron=0+0+6+*+*+MON-FRI").doesNotContain("delay=");
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "&cron=0+0+6+*+*",                // five fields
      "&cron=0+0+6+*+*+*+*",            // seven fields
      "&cron=0+0+99+*+*+*",             // no such hour
      "&cron=0+0+6+*+*+*%26delete%3Dtrue", // an encoded option
      "&cron=$%7Bheader.x%7D+0+6+*+*+*", // an expression
      "&scheduler=quartz",
  })
  void aBadCronOrTheSchedulerItselfIsRefused(String extra) {
    assertRefused(batch(SOURCE.replace("&delay=60000", extra), RECON), true, "mw-sftp-source");
  }

  @Test
  void delayAndCronTogetherAreRefused() {
    assertRefused(batch(SOURCE + "&cron=0+0+6+*+*+*", RECON), true, "delay or cron, not both");
  }

  @Test
  void aDelayOfAHundredMillisecondsIsTheShortest() {
    assertThat(load(batch(SOURCE.replace("delay=60000", "delay=100"), RECON)).route().getInput().getUri())
        .contains("delay=100&");
    assertRefused(batch(SOURCE.replace("delay=60000", "delay=99"), RECON), true, "delay must be 100");
  }

  static final String SINK_WITH_MAPPING =
      "kamelet:mw-sftp-sink?host=sftp.acme.test&username=bits&directory=out&fileName=pay-${date:now:yyyyMMddHHmmss}.csv"
          + "&secretField=sftp-password&knownHostsField=sftp-known-hosts&mapping=PAY.file";

  static PartnerConfig fileRoute(Map<String, String> mappings) {
    String route = """
        - route:
            from:
              uri: direct:partner-acme
              steps:
                - to: "kamelet:mw-map?mapping=PAY.request"
                - to: "%s"
                - to: "kamelet:mw-classify?mapping=PAY.response&allowPending=true"
                - to: "kamelet:mw-audit"
        """.formatted(SINK_WITH_MAPPING);
    return new PartnerConfig(
        true, route, mappings, List.of("sftp.acme.test"), "billpay", null, null, null, null, null, null, null, null, null, null, null);
  }

  @Test
  void aRequestRouteSinkBuildsItsTextWithAJsonataMapping() {
    PartnerRouteLoader.Loaded loaded = load(fileRoute(Map.of(
        "PAY.request", "{\"ref\": reference}",
        "PAY.file", "\"reference\\n\" & ref",
        "PAY.response", "{\"outcome\": \"PENDING\", \"partnerRef\": body.fileName}")));

    assertThat(loaded.route().toString()).contains("mapping=PAY.file");
    assertThat(loaded.runtime().mapping("PAY.file")).isNotNull();
  }

  @Test
  void aDryRunCandidateNeverPutsTheFileOnThePartnersHost() {
    PartnerConfig record = fileRoute(Map.of(
        "PAY.request", "{\"ref\": reference}",
        "PAY.file", "\"reference\\n\" & ref",
        "PAY.response", "{\"outcome\": \"PENDING\", \"partnerRef\": body.fileName}"));

    String live = load(record).route().toString();
    String candidate = loader.load("acme", 3, record, new PartnerRouteLoader.DryRunTarget("http://localhost:9999", null))
        .route().toString();

    assertThat(live).contains("dryRun=false");
    assertThat(candidate).contains("dryRun=true");
    assertRefused(batch(SOURCE, "- to: \"" + SINK_WITH_MAPPING.replace("&mapping=PAY.file", "&dryRun=false") + "\"\n" + RECON),
        true, "dryRun is not allowed");
  }

  @Test
  void aSinkMappingTheRecordDoesNotHaveIsRefused() {
    assertRefused(fileRoute(Map.of(
            "PAY.request", "{\"ref\": reference}",
            "PAY.response", "{\"outcome\": \"PENDING\", \"partnerRef\": body.fileName}")),
        false, "names mapping PAY.file");
  }

  @Test
  void aBatchRouteSinkCannotMapTheFile() {
    assertRefused(batch(SOURCE, "- to: \"" + SINK_WITH_MAPPING + "\"\n" + RECON), true, "only a request route may map");
  }

  static void assertRefused(PartnerConfig config, boolean security, String problem) {
    assertThatThrownBy(() -> loader.load("acme", 3, config))
        .isInstanceOfSatisfying(PartnerConfigRefusedException.class, e -> {
          assertThat(e.problems()).anySatisfy(p -> assertThat(p).contains(problem));
          assertThat(e.security()).isEqualTo(security);
        });
  }
}
