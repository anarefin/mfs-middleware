package mfs.mw.adapter.billpay.partner.route;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.util.List;
import java.util.Map;
import mfs.mw.adapter.billpay.TestInfrastructure;
import mfs.mw.adapter.billpay.partner.PartnerCall;
import mfs.mw.adapter.billpay.partner.PartnerResponse;
import mfs.mw.adapter.billpay.partner.config.PartnerConfigStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Ticket 114 (FRS UC-10, EXT-10, CON-02): the four generated bill aggregator records (configuration
 * only, deploy/partner-routes/aggregators/generate.mjs). Each one maps a bill fetch, a paid bill
 * (refused as BILL_ALREADY_PAID), a payment with its own auth (hmac-sha256 with a key id, OAuth2
 * client credentials, WS-Security UsernameToken, Basic), the status, a reverse and a refusal through
 * its own error map, against WireMock playing the aggregator's wire format.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestInfrastructure.class)
class BillAggregatorRouteIT {

  static final String TXN = "01933b8e-7c1a-7b2e-9f3d-000000000114";
  static final String REF = "BITS114";
  static final String BILL = "1234567890";
  static final WireMockServer AGG = new WireMockServer(WireMockConfiguration.options().dynamicPort());

  static {
    AGG.start();
  }

  @DynamicPropertySource
  static void aggregators(DynamicPropertyRegistry registry) {
    registry.add("BILL_AGG_BASE_URL", () -> "http://localhost:" + AGG.port());
  }

  private final PartnerGateway gateway;
  private final PartnerConfigStore store;

  @Autowired
  BillAggregatorRouteIT(PartnerGateway gateway, PartnerConfigStore store) {
    this.gateway = gateway;
    this.store = store;
  }

  @BeforeEach
  void reset() {
    AGG.resetAll();
  }

  static Map<String, Object> input(String partnerId) {
    return Map.of("txnType", "BILL_PAY",
        "amount", Map.of("valueMinor", 125050L, "currency", "BDT", "major", "1250.50"),
        "creditParty", Map.of("type", "BILLER_ACCOUNT", "id", BILL),
        "partner", Map.of("partnerId", partnerId, "billerCode", "DESCO"));
  }

  static PartnerCall call(String partnerId, String operation) {
    return new PartnerCall(partnerId, operation, "BILL_PAY", TXN, "corr-114", REF, input(partnerId));
  }

  @Test
  void theFourAggregatorsAreSeededBillpayPartners() {
    for (String id : List.of("sslcommerz", "ekpay", "agg3", "agg4")) {
      assertThat(store.instance(id)).as(id).isPresent();
      assertThat(store.instance(id).orElseThrow().config().errorMap()).as(id).hasSize(6);
    }
  }

  @Test
  void sslcommerzSignsWithItsKeyIdAndMapsItsSnakeCaseWireFormat() {
    String base = "/sslcommerz/v1/";
    AGG.stubFor(post(urlEqualTo(base + "bills/inquiry"))
        .withHeader("X-Key-Id", matching("bits-store")).withHeader("X-Signature", matching(".+"))
        .withRequestBody(equalToJson("{\"biller_code\":\"DESCO\",\"bill_no\":\"" + BILL + "\"}"))
        .willReturn(json(200, "{\"status\":\"UNPAID\",\"bill_no\":\"" + BILL + "\",\"amount\":\"1250.50\","
            + "\"due_date\":\"2026-12-10\",\"bill_period\":\"2026-11\"}")));
    AGG.stubFor(post(urlEqualTo(base + "payments"))
        .withRequestBody(equalToJson("{\"tran_id\":\"" + REF + "\",\"biller_code\":\"DESCO\",\"bill_no\":\"" + BILL
            + "\",\"amount\":\"1250.50\"}"))
        .willReturn(json(200, "{\"status\":\"VALID\",\"val_id\":\"SSL-1\"}")));
    AGG.stubFor(get(urlEqualTo(base + "payments/" + REF)).willReturn(json(200, "{\"status\":\"VALID\",\"val_id\":\"SSL-1\"}")));
    AGG.stubFor(post(urlEqualTo(base + "payments/" + REF + "/reverse"))
        .willReturn(json(200, "{\"status\":\"REFUNDED\",\"refund_ref_id\":\"SSL-R1\"}")));

    assertThat(gateway.call(call("sslcommerz", "INQUIRY"))).isInstanceOfSatisfying(PartnerResponse.Success.class, s -> {
      assertThat(s.partnerRef()).isEqualTo(BILL);
      assertThat(s.attributes()).containsEntry("amount", "1250.50").containsEntry("billPeriod", "2026-11");
    });
    assertSuccess(gateway.call(call("sslcommerz", "PAY")), "SSL-1");
    assertSuccess(gateway.call(call("sslcommerz", "STATUS_QUERY")), "SSL-1");
    assertSuccess(gateway.call(call("sslcommerz", "REVERSE")), "SSL-R1");

    AGG.resetAll();
    AGG.stubFor(post(urlEqualTo(base + "bills/inquiry")).willReturn(json(200, "{\"status\":\"VALID\",\"bill_no\":\"" + BILL + "\"}")));
    AGG.stubFor(post(urlEqualTo(base + "payments"))
        .willReturn(json(422, "{\"error_code\":\"E102\",\"error_message\":\"Bill already paid\"}")));
    assertRejected(gateway.call(call("sslcommerz", "INQUIRY")), "BILL_ALREADY_PAID");
    assertRejected(gateway.call(call("sslcommerz", "PAY")), "BILL_ALREADY_PAID");
  }

  @Test
  void ekpayTakesAnOauth2TokenAndMapsItsCamelCaseWireFormat() {
    String base = "/ekpay/v1/";
    AGG.stubFor(post(urlEqualTo(base + "oauth/token")).withHeader("Authorization", matching("Basic .+"))
        .withRequestBody(containing("grant_type=client_credentials"))
        .willReturn(json(200, "{\"access_token\":\"tok-ekpay\",\"token_type\":\"Bearer\",\"expires_in\":3600}")));
    AGG.stubFor(post(urlEqualTo(base + "bills/inquiry")).withHeader("Authorization", matching("Bearer tok-ekpay"))
        .withRequestBody(equalToJson("{\"billerId\":\"DESCO\",\"billNo\":\"" + BILL + "\"}"))
        .willReturn(json(200, "{\"status\":\"DUE\",\"billNo\":\"" + BILL + "\",\"amount\":\"1250.50\",\"dueDate\":\"2026-12-10\","
            + "\"billMonth\":\"2026-11\"}")));
    AGG.stubFor(post(urlEqualTo(base + "payments")).withHeader("Authorization", matching("Bearer tok-ekpay"))
        .withRequestBody(equalToJson("{\"requestId\":\"" + REF + "\",\"billerId\":\"DESCO\",\"billNo\":\"" + BILL
            + "\",\"amount\":\"1250.50\"}"))
        .willReturn(json(202, "{\"status\":\"PROCESSING\",\"trxId\":\"EK-1\"}")));
    AGG.stubFor(get(urlEqualTo(base + "payments/" + REF)).willReturn(json(200, "{\"status\":\"SUCCESS\",\"trxId\":\"EK-1\"}")));
    AGG.stubFor(post(urlEqualTo(base + "payments/" + REF + "/reverse"))
        .willReturn(json(200, "{\"status\":\"REVERSED\",\"reversalTrxId\":\"EK-R1\"}")));

    assertThat(gateway.call(call("ekpay", "INQUIRY"))).isInstanceOfSatisfying(PartnerResponse.Success.class,
        s -> assertThat(s.attributes()).containsEntry("dueDate", "2026-12-10"));
    assertThat(gateway.call(call("ekpay", "PAY"))).isInstanceOf(PartnerResponse.Pending.class);
    assertSuccess(gateway.call(call("ekpay", "STATUS_QUERY")), "EK-1");
    assertSuccess(gateway.call(call("ekpay", "REVERSE")), "EK-R1");

    AGG.stubFor(post(urlEqualTo(base + "payments"))
        .willReturn(json(422, "{\"respCode\":\"403\",\"respMsg\":\"Refused\"}")));
    assertRejected(gateway.call(call("ekpay", "PAY")), "PAYMENT_REFUSED");
  }

  @Test
  void agg3SpeaksSoapWithAUsernameTokenAndMapsAFault() {
    String soap = "/agg3/v1/soap";
    AGG.stubFor(post(urlEqualTo(soap)).withRequestBody(containing("GetBill"))
        .withRequestBody(containing("<wsse:Username>bits</wsse:Username>"))
        .withRequestBody(containing("<billNumber>" + BILL + "</billNumber>"))
        .willReturn(xml(200, envelope("<GetBillResponse xmlns=\"urn:agg3:v1\"><status>UNPAID</status><billNumber>" + BILL
            + "</billNumber><amountDue>1250.50</amountDue><dueDate>2026-12-10</dueDate><billPeriod>2026-11</billPeriod>"
            + "</GetBillResponse>"))));
    AGG.stubFor(post(urlEqualTo(soap)).withRequestBody(containing("PayBill"))
        .withRequestBody(containing("<amount>1250.50</amount>"))
        .willReturn(xml(200, envelope("<PayBillResponse xmlns=\"urn:agg3:v1\"><status>PAID</status><receiptNo>A3-1</receiptNo>"
            + "</PayBillResponse>"))));
    AGG.stubFor(post(urlEqualTo(soap)).withRequestBody(containing("PaymentStatus"))
        .willReturn(xml(200, envelope("<PaymentStatusResponse xmlns=\"urn:agg3:v1\"><status>PAID</status><receiptNo>A3-1</receiptNo>"
            + "</PaymentStatusResponse>"))));
    AGG.stubFor(post(urlEqualTo(soap)).withRequestBody(containing("ReversePayment"))
        .willReturn(xml(200, envelope("<ReversePaymentResponse xmlns=\"urn:agg3:v1\"><status>REVERSED</status>"
            + "<reversalRef>A3-R1</reversalRef></ReversePaymentResponse>"))));

    assertThat(gateway.call(call("agg3", "INQUIRY"))).isInstanceOfSatisfying(PartnerResponse.Success.class,
        s -> assertThat(s.attributes()).containsEntry("amount", "1250.50"));
    assertSuccess(gateway.call(call("agg3", "PAY")), "A3-1");
    assertSuccess(gateway.call(call("agg3", "STATUS_QUERY")), "A3-1");
    assertSuccess(gateway.call(call("agg3", "REVERSE")), "A3-R1");
    AGG.verify(postRequestedFor(urlEqualTo(soap)).withRequestBody(containing("<transactionId>" + REF + "</transactionId>")));

    AGG.resetAll();
    AGG.stubFor(post(urlEqualTo(soap)).withRequestBody(containing("GetBill"))
        .willReturn(xml(200, envelope("<GetBillResponse xmlns=\"urn:agg3:v1\"><status>PAID</status><billNumber>" + BILL
            + "</billNumber></GetBillResponse>"))));
    AGG.stubFor(post(urlEqualTo(soap)).withRequestBody(containing("PayBill")).willReturn(xml(500, envelope(
        "<soap:Fault><faultcode>soap:Client</faultcode><faultstring>refused</faultstring><detail>"
            + "<b:code xmlns:b=\"urn:agg3:v1\">A3-02</b:code><b:message xmlns:b=\"urn:agg3:v1\">Paid</b:message>"
            + "</detail></soap:Fault>"))));
    assertRejected(gateway.call(call("agg3", "INQUIRY")), "BILL_ALREADY_PAID");
    assertRejected(gateway.call(call("agg3", "PAY")), "BILL_ALREADY_PAID");
  }

  @Test
  void agg4SendsBasicAuthAndItsOwnEnvelope() {
    String base = "/agg4/v1/";
    AGG.stubFor(post(urlEqualTo(base + "bills/inquiry")).withHeader("Authorization", matching("Basic .+"))
        .withRequestBody(equalToJson("{\"req\":{\"svc\":\"DESCO\",\"acct\":\"" + BILL + "\"}}"))
        .willReturn(json(200, "{\"res\":{\"state\":\"OPEN\",\"acct\":\"" + BILL + "\",\"amt\":\"1250.50\",\"due\":\"2026-12-10\","
            + "\"month\":\"2026-11\"}}")));
    AGG.stubFor(post(urlEqualTo(base + "payments")).withHeader("Authorization", matching("Basic .+"))
        .withRequestBody(equalToJson("{\"req\":{\"ref\":\"" + REF + "\",\"svc\":\"DESCO\",\"acct\":\"" + BILL
            + "\",\"amt\":\"1250.50\"}}"))
        .willReturn(json(200, "{\"res\":{\"state\":\"DONE\",\"rcpt\":\"A4-1\"}}")));
    AGG.stubFor(get(urlEqualTo(base + "payments/" + REF)).willReturn(json(200, "{\"res\":{\"state\":\"DONE\",\"rcpt\":\"A4-1\"}}")));
    AGG.stubFor(post(urlEqualTo(base + "payments/" + REF + "/reverse"))
        .willReturn(json(200, "{\"res\":{\"state\":\"VOID\",\"rev_rcpt\":\"A4-R1\"}}")));

    assertThat(gateway.call(call("agg4", "INQUIRY"))).isInstanceOf(PartnerResponse.Success.class);
    assertSuccess(gateway.call(call("agg4", "PAY")), "A4-1");
    assertSuccess(gateway.call(call("agg4", "STATUS_QUERY")), "A4-1");
    assertSuccess(gateway.call(call("agg4", "REVERSE")), "A4-R1");

    AGG.stubFor(post(urlEqualTo(base + "payments/" + REF + "/reverse"))
        .willReturn(json(422, "{\"res\":{\"err\":\"X05\",\"msg\":\"Not paid\"}}")));
    assertRejected(gateway.call(call("agg4", "REVERSE")), "NOT_PAID");
  }

  /**
   * M9 review: a STATUS_QUERY 404 without the aggregator's own not-found marker (a gateway or load
   * balancer 404) is never final; only a 404 whose body says NOT_FOUND is.
   */
  @Test
  void aBare404OnStatusQueryIsUnknownAndOnlyTheAggregatorsNotFoundBodyIsFinal() {
    Map<String, String[]> rest = Map.of(
        "sslcommerz", new String[] {"{\"status\":\"NOT_FOUND\",\"error_code\":\"4040\"}", "status"},
        "ekpay", new String[] {"{\"status\":\"NOT_FOUND\",\"respCode\":\"4040\"}", "status"},
        "agg4", new String[] {"{\"res\":{\"state\":\"NOT_FOUND\",\"err\":\"4040\"}}", "state"});
    rest.forEach((id, notFound) -> {
      String path = "/" + id + "/v1/payments/" + REF;
      AGG.resetAll();
      stubToken();
      AGG.stubFor(get(urlEqualTo(path)).willReturn(aResponse().withStatus(404).withHeader("Content-Type", "text/html")
          .withBody("<html>404 Not Found</html>")));
      assertThat(gateway.call(call(id, "STATUS_QUERY"))).as(id + " bare 404").isInstanceOf(PartnerResponse.Unknown.class);
      AGG.resetAll();
      stubToken();
      AGG.stubFor(get(urlEqualTo(path)).willReturn(json(404, "{\"error\":\"no route\"}")));
      assertThat(gateway.call(call(id, "STATUS_QUERY"))).as(id + " json 404").isInstanceOf(PartnerResponse.Unknown.class);
      AGG.resetAll();
      stubToken();
      AGG.stubFor(get(urlEqualTo(path)).willReturn(json(404, notFound[0])));
      assertThat(gateway.call(call(id, "STATUS_QUERY"))).as(id + " NOT_FOUND body").isInstanceOf(PartnerResponse.NotFound.class);
    });
  }

  static void stubToken() {
    AGG.stubFor(post(urlEqualTo("/ekpay/v1/oauth/token"))
        .willReturn(json(200, "{\"access_token\":\"tok-ekpay\",\"token_type\":\"Bearer\",\"expires_in\":3600}")));
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

  static ResponseDefinitionBuilder xml(int status, String body) {
    return aResponse().withStatus(status).withHeader("Content-Type", "text/xml").withBody(body);
  }

  static String envelope(String body) {
    return "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body>" + body
        + "</soap:Body></soap:Envelope>";
  }
}
