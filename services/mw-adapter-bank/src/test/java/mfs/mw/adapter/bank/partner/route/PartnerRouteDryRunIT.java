package mfs.mw.adapter.bank.partner.route;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import mfs.mw.adapter.bank.TestInfrastructure;
import mfs.mw.adapter.bank.contracts.ContractSchemas;
import mfs.mw.adapter.bank.dto.ConfigChangedEvent;
import mfs.mw.adapter.bank.messaging.PartnerRouteStatusPublisher;
import mfs.mw.adapter.bank.partner.PartnerCall;
import mfs.mw.adapter.bank.partner.PartnerResponse;
import mfs.mw.adapter.bank.partner.config.PartnerConfigStore;
import mfs.mw.adapter.bank.partner.config.PartnerProperties;
import org.apache.camel.CamelContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The dry-run before a partner record version goes live (ADR D26 guardrail 3, ticket 83), in the
 * running service against two WireMock servers: the partner itself and the dry-run target (the
 * dev stack's mw-simulators stub {@code /sim/partners/<id>/**}, {@code
 * mw.partners.dry-run.base-url}). A version whose dry-run passes is swapped in and reported LIVE,
 * and the next payment goes to the partner without a restart; a version whose dry-run fails is
 * reported REJECTED with its problems while the previous version serves every call, during the
 * dry-run and after it. Each status is an outbox row that matches {@code mw.partner.route.status.v1}
 * and carries this adapter's family (identical in telco, billpay and bank, ticket 84).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestInfrastructure.class)
@DirtiesContext
class PartnerRouteDryRunIT {

  static final WireMockServer PARTNER = new WireMockServer(WireMockConfiguration.options().dynamicPort());
  static final WireMockServer SIM = new WireMockServer(WireMockConfiguration.options().dynamicPort());
  static final String PAYMENTS = "/v1/payments";
  static final String SIM_PAYMENTS = "/sim/partners/newco/v1/payments";
  static final JsonMapper JSON = JsonMapper.builder().build();

  static {
    PARTNER.start();
    SIM.start();
  }

  @DynamicPropertySource
  static void dryRunTarget(DynamicPropertyRegistry registry) {
    registry.add("mw.partners.dry-run.base-url", () -> "http://localhost:" + SIM.port() + "/sim/partners/{partnerId}");
  }

  private final PartnerConfigStore store;
  private final PartnerGateway gateway;
  private final JdbcTemplate jdbc;
  private final CamelContext camel;
  private final String family;

  @Autowired
  PartnerRouteDryRunIT(
      PartnerConfigStore store, PartnerGateway gateway, JdbcTemplate jdbc, CamelContext camel, PartnerProperties properties) {
    this.store = store;
    this.gateway = gateway;
    this.jdbc = jdbc;
    this.camel = camel;
    this.family = properties.family();
  }

  @BeforeEach
  void reset() {
    PARTNER.resetAll();
    SIM.resetAll();
  }

  /** The newco record as mw-admin-api publishes an UPSERT_ROUTE; {@code tag} marks the version in the request. */
  Map<String, Object> newco(String tag) {
    String route = """
        - route:
            from:
              uri: direct:partner-newco
              steps:
                - to: "kamelet:mw-map?mapping=PAY.request"
                - to: "kamelet:mw-http-call?url=http://localhost:%d&method=POST&path=/v1/payments"
                - to: "kamelet:mw-classify?mapping=PAY.response&definiteStatuses=422"
                - to: "kamelet:mw-audit"
        """.formatted(PARTNER.port());
    return Map.of(
        "family", family,
        "allowedHosts", List.of("localhost"),
        "timeoutMs", 3000,
        "route", route,
        "mappings", Map.of(
            "PAY.request", "{\"ref\": reference, \"account\": creditParty.id, \"tag\": \"" + tag + "\"}",
            "PAY.response", "status = 200 ? {\"outcome\": \"SUCCESS\", \"partnerRef\": body.receipt}"
                + " : {\"outcome\": \"UNKNOWN\", \"reason\": \"UNEXPECTED_STATUS\"}"),
        "dryRun", Map.of(
            "operation", "PAY",
            "sample", Map.of("creditParty", Map.of("type", "BILLER_ACCOUNT", "id", "77001234"))));
  }

  void publish(long version, Map<String, Object> value) {
    ConfigChangedEvent event = new ConfigChangedEvent(
        1, "partner/newco", ConfigChangedEvent.ConfigType.PARTNER, ConfigChangedEvent.Change.UPSERT, version, value,
        Instant.parse("2026-11-18T10:00:00Z"), "ops-maker01", "ops-checker01", "corr-cfg");
    assertThat(ContractSchemas.schema("events/mw-admin-api/mw.config.changed.v1.schema.json").validate(JSON.valueToTree(event)))
        .isEmpty();
    store.apply(event);
  }

  static void answer(WireMockServer server, String path, int status, int delayMs) {
    server.stubFor(post(urlEqualTo(path)).willReturn(aResponse().withStatus(status).withFixedDelay(delayMs)
        .withHeader("Content-Type", "application/json").withBody("{\"receipt\":\"NC-1\"}")));
  }

  static PartnerCall pay(String reference) {
    return new PartnerCall("newco", "PAY", "BILL_PAY", "01933b8e-7c1a-7b2e-9f3d-000000000083", "corr-83", reference,
        Map.of("creditParty", Map.of("type", "BILLER_ACCOUNT", "id", "77001234")));
  }

  /** The status row of {@code version}, once the dry-run worker wrote it. */
  JsonNode status(long version) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
    while (System.nanoTime() < deadline) {
      List<String> rows = jdbc.queryForList(
          "select payload::text from outbox where topic = ? and payload->>'version' = ? order by id",
          String.class, PartnerRouteStatusPublisher.TOPIC, String.valueOf(version));
      if (!rows.isEmpty()) {
        JsonNode body = JSON.readTree(rows.getLast());
        assertThat(ContractSchemas.schema("events/mw-adapter-billpay/mw.partner.route.status.v1.schema.json").validate(body))
            .as("%s", body).isEmpty();
        assertThat(body.path("family").asString()).isEqualTo(family);
        return body;
      }
      Thread.sleep(50);
    }
    throw new AssertionError("no route status for version " + version);
  }

  @Test
  void aPassingDryRunGoesLiveAndAFailingOneIsRejectedWhileThePreviousVersionServes() throws Exception {
    answer(SIM, SIM_PAYMENTS, 200, 0);
    answer(PARTNER, PAYMENTS, 200, 0);

    // 1. a new biller: dry-run against the simulator, LIVE, then a payment without a restart
    publish(1, newco("v1"));
    JsonNode live = status(1);
    assertThat(live.path("status").asString()).isEqualTo("LIVE");
    assertThat(live.path("kamelets").toString()).contains("mw-http-call");
    SIM.verify(1, postRequestedFor(urlEqualTo(SIM_PAYMENTS)));
    PARTNER.verify(0, postRequestedFor(urlEqualTo(PAYMENTS)));
    assertThat(gateway.call(pay("BITS-83-A"))).isInstanceOf(PartnerResponse.Success.class);
    PARTNER.verify(1, postRequestedFor(urlEqualTo(PAYMENTS)));
    assertThat(camel.getRoute(PartnerRuntime.candidateRouteId("newco", 1))).isNull();

    // 2. a version whose dry-run fails (a slow 500): REJECTED, v1 serves during and after
    SIM.resetAll();
    answer(SIM, SIM_PAYMENTS, 500, 1500);
    publish(2, newco("v2"));
    while (SIM.findAll(postRequestedFor(urlEqualTo(SIM_PAYMENTS))).isEmpty()) {
      Thread.onSpinWait();
    }
    CompletableFuture<PartnerResponse> during = CompletableFuture.supplyAsync(() -> gateway.call(pay("BITS-83-DURING")));
    assertThat(during.get(5, TimeUnit.SECONDS)).isInstanceOf(PartnerResponse.Success.class);
    JsonNode rejected = status(2);
    assertThat(rejected.path("status").asString()).isEqualTo("REJECTED");
    assertThat(rejected.path("problems").toString()).contains("HTTP_5XX");
    assertThat(gateway.call(pay("BITS-83-AFTER"))).isInstanceOf(PartnerResponse.Success.class);

    assertThat(store.instance("newco").orElseThrow().version()).isEqualTo(1);
    List<String> bodies = PARTNER.findAll(postRequestedFor(urlEqualTo(PAYMENTS))).stream().map(r -> r.getBodyAsString()).toList();
    assertThat(bodies).hasSize(3).allSatisfy(b -> assertThat(b).contains("\"tag\":\"v1\""));
    assertThat(camel.getRoute(PartnerRuntime.routeId("newco", 2))).isNull();
    assertThat(camel.getRoute(PartnerRuntime.candidateRouteId("newco", 2))).isNull();
  }

  @Test
  void aRecordOfAnotherFamilyIsNotThisAdaptersAndIsNotReported() throws Exception {
    String other = "billpay".equals(family) ? "telco" : "billpay";
    store.apply(new ConfigChangedEvent(
        1, "partner/ocs-x", ConfigChangedEvent.ConfigType.PARTNER, ConfigChangedEvent.Change.UPSERT, 1,
        Map.of("family", other, "route", "- route: {}"), Instant.now(), "m", "c", "corr"));

    assertThat(store.instance("ocs-x")).isEmpty();
    assertThat(jdbc.queryForObject("select count(*) from outbox where topic = ? and msg_key = 'ocs-x'", Integer.class,
        PartnerRouteStatusPublisher.TOPIC)).isZero();
  }
}
