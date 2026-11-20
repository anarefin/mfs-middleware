package mfs.mw.adapter.billpay.partner.route;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mfs.mw.adapter.billpay.TestInfrastructure;
import mfs.mw.adapter.billpay.dto.ConfigChangedEvent;
import mfs.mw.adapter.billpay.partner.PartnerCall;
import mfs.mw.adapter.billpay.partner.PartnerResponse;
import mfs.mw.adapter.billpay.partner.config.PartnerConfigStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * The sample connector of the developer guide (ticket 136, FRS CON-06): {@code
 * deploy/partner-routes/examples/sample-rest-biller.yaml} (checked copy in {@code
 * src/test/resources/partner-routes/}, kept identical by {@code scripts/test/test_connector_docs.sh}) loads
 * as a PARTNER record with no Java and serves INQUIRY, PAY, STATUS_QUERY, REVERSE and HEALTH_CHECK against
 * WireMock playing mw-simulators' {@code samplebiller} wire, with its API key and its own error map. Only
 * the MOCK endpoint is pointed at WireMock (its host added to the allowed hosts).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestInfrastructure.class)
class SampleConnectorRouteIT {

  static final String TXN = "01933b8e-7c1a-7b2e-9f3d-000000000136";
  static final String REF = "BITS136";
  static final String BILL = "1360000001";
  static final String KEY = "fake-samplebiller-api-key-for-tests";
  static final String BASE = "/samplebiller/v1/";
  static final WireMockServer SIM = new WireMockServer(WireMockConfiguration.options().dynamicPort());

  static {
    SIM.start();
  }

  private final PartnerGateway gateway;
  private final PartnerConfigStore store;

  @Autowired
  SampleConnectorRouteIT(PartnerGateway gateway, PartnerConfigStore store) {
    this.gateway = gateway;
    this.store = store;
  }

  @BeforeEach
  void load() throws Exception {
    SIM.resetAll();
    Map<String, Object> value;
    try (InputStream in = getClass().getResourceAsStream("/partner-routes/sample-rest-biller.yaml")) {
      @SuppressWarnings("unchecked")
      Map<String, Object> read = YAMLMapper.builder().build().readValue(in, Map.class);
      value = new LinkedHashMap<>(read);
    }
    @SuppressWarnings("unchecked")
    List<String> hosts = new ArrayList<>((List<String>) value.get("allowedHosts"));
    hosts.add("localhost");
    value.put("allowedHosts", hosts);
    @SuppressWarnings("unchecked")
    Map<String, Object> endpoints = new LinkedHashMap<>((Map<String, Object>) value.get("endpoints"));
    endpoints.put("MOCK", "http://localhost:" + SIM.port());
    value.put("endpoints", endpoints);
    store.apply(new ConfigChangedEvent(1, "partner/samplebiller", ConfigChangedEvent.ConfigType.PARTNER,
        ConfigChangedEvent.Change.UPSERT, 1, value, Instant.parse("2026-11-20T10:00:00Z"), "ops-maker01",
        "ops-checker01", "corr-136"));
    assertThat(store.instance("samplebiller")).as("the sample record loads").isPresent();
  }

  static PartnerCall call(String operation) {
    return new PartnerCall("samplebiller", operation, "BILL_PAY", TXN, "corr-136", REF, Map.of(
        "txnType", "BILL_PAY",
        "amount", Map.of("valueMinor", 84500L, "currency", "BDT", "major", "845.00"),
        "creditParty", Map.of("type", "BILLER_ACCOUNT", "id", BILL),
        "partner", Map.of("partnerId", "samplebiller", "billerCode", "SAMPLEWATER")));
  }

  @Test
  void theSampleRecordServesEveryOperationWithItsApiKey() {
    SIM.stubFor(post(urlEqualTo(BASE + "bills/inquiry")).withHeader("X-Api-Key", equalTo(KEY))
        .withRequestBody(equalToJson("{\"billerCode\":\"SAMPLEWATER\",\"billNumber\":\"" + BILL + "\"}"))
        .willReturn(json(200, "{\"status\":\"DUE\",\"billerCode\":\"SAMPLEWATER\",\"billNumber\":\"" + BILL + "\","
            + "\"amount\":\"845.00\",\"dueDate\":\"2026-12-10\",\"billPeriod\":\"2026-11\"}")));
    SIM.stubFor(post(urlEqualTo(BASE + "payments")).withHeader("X-Api-Key", equalTo(KEY))
        .withRequestBody(equalToJson("{\"transactionId\":\"" + REF + "\",\"billerCode\":\"SAMPLEWATER\",\"billNumber\":\""
            + BILL + "\",\"amount\":\"845.00\"}"))
        .willReturn(json(200, "{\"transactionId\":\"" + REF + "\",\"status\":\"PAID\",\"receipt\":\"SAMPLEBILLER-1\"}")));
    SIM.stubFor(get(urlEqualTo(BASE + "payments/" + REF)).withHeader("X-Api-Key", equalTo(KEY))
        .willReturn(json(200, "{\"transactionId\":\"" + REF + "\",\"status\":\"PAID\",\"receipt\":\"SAMPLEBILLER-1\"}")));
    SIM.stubFor(post(urlEqualTo(BASE + "payments/" + REF + "/reverse")).withHeader("X-Api-Key", equalTo(KEY))
        .willReturn(json(200, "{\"transactionId\":\"" + REF + "\",\"status\":\"REVERSED\",\"reversalRef\":\"SAMPLEBILLER-R2\"}")));
    SIM.stubFor(get(urlEqualTo(BASE + "health")).willReturn(json(200, "{\"status\":\"UP\"}")));

    assertThat(gateway.call(call("INQUIRY"))).isInstanceOfSatisfying(PartnerResponse.Success.class, s -> {
      assertThat(s.partnerRef()).isEqualTo(BILL);
      assertThat(s.attributes()).containsEntry("amount", "845.00").containsEntry("dueDate", "2026-12-10")
          .containsEntry("billPeriod", "2026-11");
    });
    assertSuccess(gateway.call(call("PAY")), "SAMPLEBILLER-1");
    assertSuccess(gateway.call(call("STATUS_QUERY")), "SAMPLEBILLER-1");
    assertSuccess(gateway.call(call("REVERSE")), "SAMPLEBILLER-R2");
    assertThat(gateway.call(call("HEALTH_CHECK"))).isInstanceOf(PartnerResponse.Success.class);
  }

  @Test
  void refusalsGoThroughTheSampleErrorMap() {
    SIM.stubFor(post(urlEqualTo(BASE + "bills/inquiry"))
        .willReturn(json(200, "{\"status\":\"PAID\",\"billerCode\":\"SAMPLEWATER\",\"billNumber\":\"" + BILL + "\"}")));
    SIM.stubFor(post(urlEqualTo(BASE + "payments"))
        .willReturn(json(422, "{\"status\":\"FAILED\",\"code\":\"SB06\",\"message\":\"Amount mismatch\"}")));
    assertRejected(gateway.call(call("INQUIRY")), "BILL_ALREADY_PAID");
    assertRejected(gateway.call(call("PAY")), "AMOUNT_MISMATCH");
  }

  @Test
  void anAmbiguousCodeIsAmbiguousNotAFailure() {
    SIM.stubFor(post(urlEqualTo(BASE + "payments"))
        .willReturn(json(422, "{\"status\":\"FAILED\",\"code\":\"9876\",\"message\":\"Refused\"}")));
    assertThat(gateway.call(call("PAY"))).isInstanceOf(PartnerResponse.Ambiguous.class);
  }

  static void assertSuccess(PartnerResponse response, String partnerRef) {
    assertThat(response).isInstanceOfSatisfying(PartnerResponse.Success.class, s -> assertThat(s.partnerRef()).isEqualTo(partnerRef));
  }

  static void assertRejected(PartnerResponse response, String suffix) {
    assertThat(response).isInstanceOfSatisfying(PartnerResponse.Rejected.class, r -> assertThat(r.errorSuffix()).isEqualTo(suffix));
  }

  static ResponseDefinitionBuilder json(int status, String body) {
    return aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body);
  }
}
