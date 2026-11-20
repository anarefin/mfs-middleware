package mfs.mw.adapter.interop.partner.route;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import mfs.mw.adapter.interop.TestInfrastructure;
import mfs.mw.adapter.interop.dto.ConfigChangedEvent;
import mfs.mw.adapter.interop.partner.PartnerCall;
import mfs.mw.adapter.interop.partner.PartnerResponse;
import mfs.mw.adapter.interop.partner.config.PartnerConfigStore;
import mfs.mw.adapter.interop.partner.config.PartnerProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

/**
 * The maintenance window and the endpoint profile of the partner engine (ticket 131b, FRS PLT-043,
 * 10.4 criterion 3), in the running service against two WireMock partners (a mock and a "real"
 * one): inside a window (or with the maintenance flag) a PAY is refused unsent as {@code
 * MAINTENANCE} with the window's bn/en message and nothing reaches the partner; once the window is
 * over the PAY is sent. A route calling {@code url={{endpoint}}} goes to the selected profile's base
 * URL, and a new record version selecting the other profile sends the next call there, with no
 * route edit and no restart. Identical in telco, billpay and bank.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestInfrastructure.class)
@DirtiesContext
class PartnerMaintenanceEndpointIT {

  static final WireMockServer MOCK = new WireMockServer(WireMockConfiguration.options().dynamicPort());
  static final WireMockServer REAL = new WireMockServer(WireMockConfiguration.options().dynamicPort());
  static final String PATH = "/v1/pay";

  static {
    MOCK.start();
    REAL.start();
  }

  private final PartnerConfigStore store;
  private final PartnerGateway gateway;
  private final String family;
  /** The store keeps the newest version for the life of the Spring context, which the methods share. */
  private static final AtomicLong VERSIONS = new AtomicLong(1);

  @Autowired
  PartnerMaintenanceEndpointIT(PartnerConfigStore store, PartnerGateway gateway, PartnerProperties properties) {
    this.store = store;
    this.gateway = gateway;
    this.family = properties.family();
  }

  @BeforeEach
  void reset() {
    for (WireMockServer s : List.of(MOCK, REAL)) {
      s.resetAll();
      s.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200)
          .withHeader("Content-Type", "application/json").withBody("{\"status\":\"OK\",\"ref\":\"P-1\"}")));
    }
  }

  void publish(Map<String, Object> extra) {
    String route = """
        - route:
            from:
              uri: direct:partner-mainco
              steps:
                - to: "kamelet:mw-map?mapping=PAY.request"
                - to: "kamelet:mw-http-call?url={{endpoint}}&method=POST&path=/v1/pay"
                - to: "kamelet:mw-classify?mapping=PAY.response&definiteStatuses=422"
                - to: "kamelet:mw-audit"
        """;
    Map<String, Object> value = new LinkedHashMap<>(Map.of(
        "family", family,
        "allowedHosts", List.of("localhost"),
        "timeoutMs", 3000,
        "endpoint", "MOCK",
        "endpoints", Map.of("MOCK", "http://localhost:" + MOCK.port(), "REAL", "http://localhost:" + REAL.port()),
        "route", route,
        "mappings", Map.of(
            "PAY.request", "{\"ref\": reference}",
            "PAY.response", "status = 200 ? {\"outcome\": \"SUCCESS\", \"partnerRef\": body.ref, \"code\": \"00\"}"
                + " : {\"outcome\": \"UNKNOWN\", \"reason\": \"UNEXPECTED_STATUS\"}")));
    value.putAll(extra);
    long v = VERSIONS.getAndIncrement();
    store.apply(new ConfigChangedEvent(
        1, "partner/mainco", ConfigChangedEvent.ConfigType.PARTNER, ConfigChangedEvent.Change.UPSERT, v, value,
        Instant.parse("2026-11-20T10:00:00Z"), "ops-maker01", "ops-checker01", "corr-131b"));
    assertThat(store.instance("mainco")).as("version %d loads", v).isPresent();
    assertThat(store.entries().get("mainco").version()).isEqualTo(v);
  }

  static PartnerCall pay() {
    return new PartnerCall("mainco", "PAY", "BILL_PAY", "01933b8e-7c1a-7b2e-9f3d-000000000131", "corr-131b", "BITS-131",
        Map.of("amount", Map.of("major", "20.00")));
  }

  @Test
  void insideAWindowAPayIsRefusedWithItsMessageAndNothingIsSent() {
    Instant now = Instant.now();
    publish(Map.of("maintenanceWindow", Map.of(
        "start", now.minus(1, ChronoUnit.HOURS).toString(), "end", now.plus(1, ChronoUnit.HOURS).toString(),
        "messageEn", "Top-up is under maintenance until 4 pm", "messageBn", "বিকেল ৪টা পর্যন্ত টপ-আপ রক্ষণাবেক্ষণে আছে")));

    PartnerResponse response = gateway.call(pay());

    assertThat(response).isEqualTo(new PartnerResponse.NotSent(PartnerResponse.NotSentReason.MAINTENANCE,
        new PartnerResponse.Notice("Top-up is under maintenance until 4 pm", "বিকেল ৪টা পর্যন্ত টপ-আপ রক্ষণাবেক্ষণে আছে")));
    MOCK.verify(0, anyRequestedFor(anyUrl()));
    REAL.verify(0, anyRequestedFor(anyUrl()));
  }

  @Test
  void theMaintenanceFlagRefusesWithTheDefaultMessage() {
    publish(Map.of("maintenance", true));

    assertThat(gateway.call(pay())).isInstanceOfSatisfying(PartnerResponse.NotSent.class, n -> {
      assertThat(n.reason()).isEqualTo(PartnerResponse.NotSentReason.MAINTENANCE);
      assertThat(n.notice().en()).isNotBlank();
      assertThat(n.notice().bn()).isNotBlank();
    });
    MOCK.verify(0, anyRequestedFor(anyUrl()));
  }

  @Test
  void aWindowThatIsOverOrNotYetOpenSendsThePay() {
    Instant now = Instant.now();
    publish(Map.of("maintenanceWindow", Map.of(
        "start", now.minus(2, ChronoUnit.HOURS).toString(), "end", now.minus(1, ChronoUnit.HOURS).toString())));
    assertThat(gateway.call(pay())).isEqualTo(new PartnerResponse.Success("P-1", "00"));

    publish(Map.of("maintenanceWindow", Map.of("start", now.plus(1, ChronoUnit.HOURS).toString())));
    assertThat(gateway.call(pay())).isEqualTo(new PartnerResponse.Success("P-1", "00"));
    MOCK.verify(2, postRequestedFor(urlEqualTo(PATH)));
  }

  @Test
  void switchingTheEndpointProfileSendsTheNextCallToTheOtherBaseUrl() {
    publish(Map.of());
    assertThat(gateway.call(pay())).isEqualTo(new PartnerResponse.Success("P-1", "00"));
    MOCK.verify(1, postRequestedFor(urlEqualTo(PATH)));
    REAL.verify(0, anyRequestedFor(anyUrl()));

    publish(Map.of("endpoint", "REAL"));
    assertThat(gateway.call(pay())).isEqualTo(new PartnerResponse.Success("P-1", "00"));
    REAL.verify(1, postRequestedFor(urlEqualTo(PATH)));
    MOCK.verify(1, postRequestedFor(urlEqualTo(PATH)));
  }
}
