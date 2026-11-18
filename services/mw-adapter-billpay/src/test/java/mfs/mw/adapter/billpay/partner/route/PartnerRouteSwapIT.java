package mfs.mw.adapter.billpay.partner.route;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import mfs.mw.adapter.billpay.TestInfrastructure;
import mfs.mw.adapter.billpay.dto.ConfigChangedEvent;
import mfs.mw.adapter.billpay.mapper.PartnerCallMapper;
import mfs.mw.adapter.billpay.partner.PartnerCall;
import mfs.mw.adapter.billpay.partner.PartnerResponse;
import mfs.mw.adapter.billpay.partner.config.PartnerConfig;
import mfs.mw.adapter.billpay.partner.config.PartnerConfigStore;
import org.apache.camel.CamelContext;
import org.apache.camel.Endpoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

/**
 * The partner route lifecycle in the running service (ADR D26, ticket 82), through {@link
 * PartnerGateway} against WireMock: a call in flight during a swap completes on the old version and
 * the next one uses the new; a refused record keeps the last good route live; a PAY is sent once
 * on a timeout while an INQUIRY goes again; an unknown partnerId is refused and an alias is
 * served; partners show in health and metrics, and no endpoint URI carries a bill number or secret.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "mw.partners.instances.billagg.timeout-ms=1000",
      "mw.partners.instances.billagg.read-timeout-ms=3000",
      "mw.partners.partner-aliases.utility=billagg",
      "resilience4j.circuitbreaker.instances.billagg.minimum-number-of-calls=100"
    })
@Import(TestInfrastructure.class)
@DirtiesContext
class PartnerRouteSwapIT {

  static final WireMockServer AGG = new WireMockServer(WireMockConfiguration.options().dynamicPort());
  static final String BILL = "77001234";

  static {
    AGG.start();
  }

  @DynamicPropertySource
  static void aggregatorUrl(DynamicPropertyRegistry registry) {
    registry.add("mw.partners.instances.billagg.base-url", () -> "http://localhost:" + AGG.port());
  }

  @Autowired PartnerGateway gateway;
  @Autowired PartnerConfigStore store;
  @Autowired PartnerRoutes routes;
  @Autowired PartnerRouteHealth health;
  @Autowired CamelContext camel;
  @Autowired MeterRegistry meters;
  @Autowired CircuitBreakerRegistry breakers;
  @Autowired JsonMapper json;
  final PartnerCallMapper calls = new PartnerCallMapper();

  @BeforeEach
  void reset() {
    AGG.resetAll();
    breakers.circuitBreaker("billagg").reset();
  }

  static PartnerCall pay(String partnerId, String reference) {
    return new PartnerCall(
        partnerId, "PAY", "BILL_PAY", "01933b8e-7c1a-7b2e-9f3d-000000000001", "corr-1", reference,
        Map.of("amount", Map.of("major", "20.00"), "creditParty", Map.of("type", "BILLER_ACCOUNT", "id", BILL),
            "partner", Map.of("partnerId", partnerId, "billerCode", "DESCO")));
  }

  void stubPay(int delayMs) {
    AGG.stubFor(post(urlEqualTo("/billagg/v1/payments"))
        .willReturn(aResponse().withStatus(200).withFixedDelay(delayMs).withHeader("Content-Type", "application/json")
            .withBody("{\"status\":\"SUCCESS\",\"code\":\"0000\",\"paymentRef\":\"AGG-1\"}")));
  }

  /** The live billagg record with {@code PAY.request} mapping {@code request}, as the next version. */
  void publish(long version, String payRequest) {
    PartnerConfig live = store.instance("billagg").orElseThrow().config();
    Map<String, String> mappings = new LinkedHashMap<>(live.mappings());
    mappings.put("PAY.request", payRequest);
    @SuppressWarnings("unchecked")
    Map<String, Object> value = json.convertValue(live.withRoute(live.route(), mappings, live.allowedHosts()), Map.class);
    store.apply(new ConfigChangedEvent(
        1, "partner/billagg", ConfigChangedEvent.ConfigType.PARTNER, ConfigChangedEvent.Change.UPSERT, version, value,
        Instant.now(), "ops.maker1", "ops.checker1", "corr-cfg"));
  }

  @Test
  void aCallInFlightDuringASwapCompletesOnTheOldVersionAndTheNextUsesTheNew() throws Exception {
    stubPay(700);
    long before = store.entries().get("billagg").version();
    PartnerRuntime old = store.instance("billagg").orElseThrow();

    CompletableFuture<PartnerResponse> inFlight = CompletableFuture.supplyAsync(() -> gateway.call(pay("billagg", "BITS-OLD")));
    while (AGG.findAll(postRequestedFor(urlEqualTo("/billagg/v1/payments"))).isEmpty()) {
      Thread.onSpinWait();
    }
    publish(before + 1, "{\"transactionId\": reference, \"billNumber\": creditParty.id, \"amount\": amount.major, \"channel\": \"v2\"}");
    PartnerResponse next = gateway.call(pay("billagg", "BITS-NEW"));

    assertThat(inFlight.get(5, TimeUnit.SECONDS)).isInstanceOf(PartnerResponse.Success.class);
    assertThat(next).isInstanceOf(PartnerResponse.Success.class);
    List<String> bodies = AGG.findAll(postRequestedFor(urlEqualTo("/billagg/v1/payments"))).stream()
        .map(r -> r.getBodyAsString()).toList();
    assertThat(bodies.stream().filter(b -> b.contains("BITS-OLD")).findFirst().orElseThrow()).doesNotContain("channel");
    assertThat(bodies.stream().filter(b -> b.contains("BITS-NEW")).findFirst().orElseThrow()).contains("\"channel\":\"v2\"");
    // the old version and its Kamelet routes are gone once the call finished
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (camel.getRoute(old.routeId()) != null && System.nanoTime() < deadline) {
      Thread.sleep(50);
    }
    assertThat(camel.getRoute(old.routeId())).isNull();
    assertThat(camel.getRoutes()).noneMatch(r -> old.routeId().equals(r.getProperties().get(org.apache.camel.Route.PARENT_PROPERTY)));
  }

  @Test
  void aRefusedRecordKeepsTheLastGoodRouteLive() {
    stubPay(0);
    PartnerRuntime live = store.instance("billagg").orElseThrow();
    PartnerConfig config = live.config();
    @SuppressWarnings("unchecked")
    Map<String, Object> value = json.convertValue(
        config.withRoute(config.route().replace("kamelet:mw-audit", "exec:rm"), config.mappings(), config.allowedHosts()), Map.class);

    boolean applied = store.apply(new ConfigChangedEvent(
        1, "partner/billagg", ConfigChangedEvent.ConfigType.PARTNER, ConfigChangedEvent.Change.UPSERT,
        live.version() + 100, value, Instant.now(), "ops.maker1", "ops.checker1", "corr-cfg"));

    assertThat(applied).isFalse();
    assertThat(store.instance("billagg").orElseThrow().routeId()).isEqualTo(live.routeId());
    assertThat(gateway.call(pay("billagg", "BITS-STILL"))).isInstanceOf(PartnerResponse.Success.class);
  }

  @Test
  void aPayIsSentExactlyOnceWhenThePartnerTimesOutWhileAnInquiryGoesAgain() {
    stubPay(2000);
    AGG.stubFor(post(urlEqualTo("/billagg/v1/bills/fetch")).willReturn(aResponse().withStatus(200).withFixedDelay(2000)));

    PartnerResponse pay = gateway.call(pay("billagg", "BITS-SLOW"));
    PartnerResponse fetch = gateway.call(calls.fetchCall("billagg", "DESCO", BILL, "corr-2"));

    assertThat(pay).isEqualTo(new PartnerResponse.Unknown(PartnerResponse.UnknownReason.TIMEOUT));
    assertThat(fetch).isEqualTo(new PartnerResponse.Unknown(PartnerResponse.UnknownReason.TIMEOUT));
    AGG.verify(1, postRequestedFor(urlEqualTo("/billagg/v1/payments")));
    // inquiryRetries 1: the first attempt and one more
    AGG.verify(2, postRequestedFor(urlEqualTo("/billagg/v1/bills/fetch")));
  }

  @Test
  void anUnknownPartnerIsRefusedUnsentAndAnAliasIsServed() {
    stubPay(0);

    assertThat(gateway.call(pay("nobody", "BITS-X")))
        .isEqualTo(new PartnerResponse.NotSent(PartnerResponse.NotSentReason.UNKNOWN_PARTNER));
    AGG.verify(0, anyRequestedFor(anyUrl()));
    assertThat(gateway.call(pay("utility", "BITS-ALIAS"))).isInstanceOf(PartnerResponse.Success.class);
    AGG.verify(1, postRequestedFor(urlEqualTo("/billagg/v1/payments")));
  }

  @Test
  void theStatusInquiryCarriesTheReferenceInThePathAndNoMwHeaderLeavesTheService() {
    AGG.stubFor(get(urlPathMatching("/billagg/v1/payments/.*")).willReturn(aResponse().withStatus(200)
        .withHeader("Content-Type", "application/json")
        .withBody("{\"status\":\"SUCCESS\",\"code\":\"0000\",\"paymentRef\":\"AGG-9\"}")));
    PartnerCall status = new PartnerCall("billagg", "STATUS_QUERY", "BILL_PAY", "t-1", "corr-3", "BITS-REF-1", Map.of());

    assertThat(gateway.call(status)).isInstanceOf(PartnerResponse.Success.class);
    var request = AGG.findAll(getRequestedFor(urlEqualTo("/billagg/v1/payments/BITS-REF-1"))).getFirst();
    assertThat(request.getHeaders().keys()).noneMatch(h -> h.toLowerCase().startsWith("mw") || h.toLowerCase().startsWith("camel"));
    assertThat(request.getHeader("X-Signature")).isNotBlank();
    assertThat(request.getHeader("X-Correlation-Id")).isEqualTo("corr-3");
  }

  @Test
  void partnersAppearInHealthAndMetricsAndNoEndpointCarriesABillNumberOrSecret() {
    stubPay(0);
    gateway.call(pay("billagg", "BITS-HEALTH"));

    Health h = health.health();
    @SuppressWarnings("unchecked")
    Map<String, Object> billagg = (Map<String, Object>) h.getDetails().get("billagg");
    assertThat(billagg).containsEntry("status", "UP");
    assertThat(meters.getMeters())
        .anySatisfy(m -> {
          assertThat(m.getId().getName()).startsWith("camel");
          assertThat(m.getId().getTag("partnerId")).isEqualTo("billagg");
        });
    String endpoints = camel.getEndpoints().stream().map(Endpoint::getEndpointUri).toList().toString();
    assertThat(endpoints).doesNotContain(BILL).doesNotContain("BITS-HEALTH").doesNotContain("fake-billagg-hmac-secret");
  }
}
