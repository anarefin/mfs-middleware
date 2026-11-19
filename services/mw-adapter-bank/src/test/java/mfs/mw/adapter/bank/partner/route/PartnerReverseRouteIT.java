package mfs.mw.adapter.bank.partner.route;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import mfs.mw.adapter.bank.TestInfrastructure;
import mfs.mw.adapter.bank.dto.ConfigChangedEvent;
import mfs.mw.adapter.bank.partner.PartnerCall;
import mfs.mw.adapter.bank.partner.PartnerResponse;
import mfs.mw.adapter.bank.partner.config.PartnerConfigStore;
import mfs.mw.adapter.bank.partner.config.PartnerProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

/**
 * The REVERSE operation of the partner engine (ticket 97, FRS CON-01), in the running service
 * against WireMock: a route's {@code REVERSE} branch maps (the idempotent reversal reference {@code
 * reverse:<txnId>} and the original reference), calls and classifies; a 503 on a REVERSE is sent
 * once, never again by the engine or the HTTP client (only the saga resends, ticket 96), even with
 * {@code inquiryRetries} that would resend a STATUS_QUERY. Identical in telco, billpay and bank.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestInfrastructure.class)
@DirtiesContext
class PartnerReverseRouteIT {

  static final WireMockServer PARTNER = new WireMockServer(WireMockConfiguration.options().dynamicPort());
  static final String TXN = "01933b8e-7c1a-7b2e-9f3d-000000000097";
  static final String REVERSE_PATH = "/v1/payments/BITS-97/reverse";

  static {
    PARTNER.start();
  }

  private final PartnerConfigStore store;
  private final PartnerGateway gateway;
  private final String family;

  @Autowired
  PartnerReverseRouteIT(PartnerConfigStore store, PartnerGateway gateway, PartnerProperties properties) {
    this.store = store;
    this.gateway = gateway;
    this.family = properties.family();
  }

  @BeforeEach
  void publishRecord() {
    PARTNER.resetAll();
    if (store.instance("revco").isPresent()) {
      return;
    }
    String route = """
        - route:
            from:
              uri: direct:partner-revco
              steps:
                - choice:
                    when:
                      - simple: "${header.mwOperation} == 'REVERSE'"
                        steps:
                          - to: "kamelet:mw-map?mapping=REVERSE.request"
                          - to: "kamelet:mw-http-call?url=http://localhost:%d&method=POST&path=/v1/payments/{reference}/reverse"
                          - to: "kamelet:mw-classify?mapping=REVERSE.response&definiteStatuses=404,422"
                - to: "kamelet:mw-audit"
        """.formatted(PARTNER.port());
    store.apply(new ConfigChangedEvent(
        1, "partner/revco", ConfigChangedEvent.ConfigType.PARTNER, ConfigChangedEvent.Change.UPSERT, 1,
        Map.of(
            "family", family,
            "allowedHosts", List.of("localhost"),
            "timeoutMs", 3000,
            "inquiryRetries", 3,
            "route", route,
            "mappings", Map.of(
                "REVERSE.request", "{\"reversalId\": reversalReference, \"original\": reference, \"amount\": amount.major}",
                "REVERSE.response", "status = 200 and body.status = \"REVERSED\""
                    + " ? {\"outcome\": \"SUCCESS\", \"partnerRef\": body.reversalRef, \"code\": body.code}"
                    + " : status = 404 or status = 422 ? {\"outcome\": \"FAILED\", \"code\": body.code, \"message\": body.message}"
                    + " : {\"outcome\": \"UNKNOWN\", \"reason\": \"UNEXPECTED_STATUS\"}")),
        Instant.parse("2026-11-19T10:00:00Z"), "ops-maker01", "ops-checker01", "corr-97"));
    assertThat(store.instance("revco")).isPresent();
  }

  static PartnerCall reverse() {
    return new PartnerCall("revco", "REVERSE", "BILL_PAY", TXN, "corr-97", "BITS-97",
        Map.of("amount", Map.of("major", "20.00")));
  }

  static void answer(int status, String body) {
    PARTNER.stubFor(post(urlEqualTo(REVERSE_PATH)).willReturn(aResponse().withStatus(status)
        .withHeader("Content-Type", "application/json").withBody(body)));
  }

  @Test
  void aReverseFlowMapsCallsAndClassifies() {
    answer(200, "{\"status\":\"REVERSED\",\"code\":\"0000\",\"reversalRef\":\"REV-1\"}");

    PartnerResponse response = gateway.call(reverse());

    assertThat(response).isEqualTo(new PartnerResponse.Success("REV-1", "0000"));
    PARTNER.verify(1, postRequestedFor(urlEqualTo(REVERSE_PATH)).withRequestBody(
        equalToJson("{\"reversalId\":\"reverse:" + TXN + "\",\"original\":\"BITS-97\",\"amount\":\"20.00\"}")));
  }

  @Test
  void aDefiniteRefusalOfAReverseIsFailed() {
    answer(422, "{\"status\":\"FAILED\",\"code\":\"4091\",\"message\":\"cannot be reversed\"}");

    assertThat(gateway.call(reverse())).isInstanceOfSatisfying(PartnerResponse.Rejected.class,
        r -> assertThat(r.code()).isEqualTo("4091"));
  }

  @Test
  void a503OnAReverseIsSentOnceAndNeverAgain() {
    answer(503, "{\"status\":\"UNAVAILABLE\"}");

    PartnerResponse response = gateway.call(reverse());

    assertThat(response).isEqualTo(new PartnerResponse.Unknown(PartnerResponse.UnknownReason.HTTP_5XX));
    PARTNER.verify(1, postRequestedFor(urlEqualTo(REVERSE_PATH)));
  }
}
