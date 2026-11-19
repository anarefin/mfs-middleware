package mfs.mw.simulators;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The billagg SOAP front end {@code POST /billagg/v1/soap} (SOAP 1.1, {@code urn:billagg:v1}): FetchBill and
 * PayBill for the SOAP billers with a WS-Security UsernameToken (PasswordText) as the adapters'
 * {@code mw-soap-call} sends it, the same bills, magic values and payment store as the REST API (a SOAP
 * payment is found by the REST status inquiry), faults with the aggregator's codes, and the protocol
 * guard (a biller on the other protocol's endpoint is an unknown biller, 3004).
 */
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {"sim.timeout-delay=300ms", "sim.billagg.ws-password=" + BillAggregatorSoapIT.PASSWORD})
@Import(TestInfrastructure.class)
class BillAggregatorSoapIT {

  static final String PASSWORD = "fake-billagg-ws-password-for-tests";
  static final String NS = "urn:billagg:v1";
  static final String WSSE = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd";
  static final String WSU = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd";
  static final String TEXT =
      "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordText";
  private static final String BILL = "1234567";

  private final HttpClient http = HttpClient.newHttpClient();
  private final JsonMapper json = JsonMapper.builder().build();

  @LocalServerPort int port;

  /** The header as mw-adapter-billpay's MwSoapStep builds it. */
  static String security(String user, String password) {
    return "<wsse:Security xmlns:wsse=\"" + WSSE + "\" xmlns:wsu=\"" + WSU + "\"><wsse:UsernameToken>"
        + "<wsse:Username>" + user + "</wsse:Username><wsse:Password Type=\"" + TEXT + "\">" + password
        + "</wsse:Password></wsse:UsernameToken></wsse:Security>";
  }

  static String envelope(String header, String body) {
    return "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">"
        + (header == null ? "" : "<soap:Header>" + header + "</soap:Header>")
        + "<soap:Body>" + body + "</soap:Body></soap:Envelope>";
  }

  static String fetchBill(String biller, String bill) {
    return "<b:FetchBill xmlns:b=\"" + NS + "\"><b:billerCode>" + biller + "</b:billerCode><b:billNumber>" + bill
        + "</b:billNumber></b:FetchBill>";
  }

  static String payBill(String biller, String bill, String amount, String reference) {
    return "<b:PayBill xmlns:b=\"" + NS + "\"><b:billerCode>" + biller + "</b:billerCode><b:billNumber>" + bill
        + "</b:billNumber><b:amount>" + amount + "</b:amount><b:reference>" + reference + "</b:reference></b:PayBill>";
  }

  private HttpResponse<String> soap(String action, String header, String body) throws Exception {
    HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/billagg/v1/soap"))
        .header("Content-Type", "text/xml; charset=UTF-8")
        .header("SOAPAction", "\"urn:billagg:v1:" + action + "\"")
        .POST(HttpRequest.BodyPublishers.ofString(envelope(header, body)))
        .build();
    return http.send(req, HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> soap(String action, String body) throws Exception {
    return soap(action, security("bits", PASSWORD), body);
  }

  private HttpResponse<String> send(String method, String path, String body) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    if (body == null) {
      b.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
    }
    return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static String element(String xml, String name) {
    return xml.replaceAll("(?s).*<b:" + name + ">([^<]*)</b:" + name + ">.*", "$1");
  }

  @AfterEach
  void reset() throws Exception {
    send("DELETE", "/sim/billagg/scenario", null);
    send("DELETE", "/sim/billagg/requests", null);
  }

  @Test
  void fetchReturnsTheSameBillAsTheRestApi() throws Exception {
    HttpResponse<String> res = soap("FetchBill", fetchBill("KGDCL", BILL));

    assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
    assertThat(res.headers().firstValue("Content-Type")).hasValueSatisfying(v -> assertThat(v).startsWith("text/xml"));
    assertThat(res.body()).contains("<b:FetchBillResponse", "<b:billerCode>KGDCL</b:billerCode>",
        "<b:billNumber>" + BILL + "</b:billNumber>", "<b:amountDue>667.00</b:amountDue>", "<b:dueDate>", "<b:billPeriod>");
    assertThat(element(res.body(), "dueDate")).matches("\\d{4}-\\d{2}-\\d{2}");
  }

  @Test
  void payIsApprovedAndTheRestStatusInquiryFindsIt() throws Exception {
    HttpResponse<String> res = soap("PayBill", payBill("BTCL", BILL, "667.00", "SOAP-ok"));

    assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
    assertThat(res.body()).contains("<b:PayBillResponse", "<b:status>SUCCESS</b:status>", "<b:code>000</b:code>");
    String ref = element(res.body(), "paymentRef");
    assertThat(ref).startsWith("AGG-");

    HttpResponse<String> status = send("GET", "/billagg/v1/payments/SOAP-ok", null);
    assertThat(status.statusCode()).isEqualTo(200);
    JsonNode body = json.readTree(status.body());
    assertThat(body.get("status").asString()).isEqualTo("SUCCESS");
    assertThat(body.get("paymentRef").asString()).isEqualTo(ref);
  }

  @Test
  void aRepeatedReferenceReturnsTheSameReply() throws Exception {
    HttpResponse<String> first = soap("PayBill", payBill("LINK3", BILL, "667.00", "SOAP-rep"));
    HttpResponse<String> again = soap("PayBill", payBill("LINK3", BILL, "667.00", "SOAP-rep"));

    assertThat(again.statusCode()).isEqualTo(200);
    assertThat(again.body()).isEqualTo(first.body());
  }

  @Test
  void callbackOnlyModeAnswersPending() throws Exception {
    send("POST", "/sim/billagg/scenario", "{\"callbackOnly\":true}");

    HttpResponse<String> res = soap("PayBill", payBill("JGTDSL", BILL, "667.00", "SOAP-pend"));

    assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
    assertThat(res.body()).contains("<b:status>PENDING</b:status>", "<b:code>001</b:code>", "<b:paymentRef>AGG-");
    assertThat(json.readTree(send("GET", "/billagg/v1/payments/SOAP-pend", null).body()).get("status").asString())
        .isEqualTo("SUCCESS");
  }

  @Test
  void anUnknownBillAndARefusedPaymentAreFaultsWithTheAggregatorsCode() throws Exception {
    HttpResponse<String> notFound = soap("FetchBill", fetchBill("KGDCL", "1234404"));
    HttpResponse<String> refused = soap("PayBill", payBill("KGDCL", "1234999", "100.00", "SOAP-bf"));

    assertThat(notFound.statusCode()).isEqualTo(500);
    assertThat(notFound.body()).contains("<faultcode>soap:Client</faultcode>", "<b:error xmlns:b=\"" + NS + "\">", "<b:code>3001</b:code>",
        "<b:message>");
    assertThat(refused.statusCode()).isEqualTo(500);
    assertThat(refused.body()).contains("<faultcode>soap:Client</faultcode>", "<b:code>3003</b:code>");
    assertThat(json.readTree(send("GET", "/billagg/v1/payments/SOAP-bf", null).body()).get("status").asString())
        .isEqualTo("FAILED");
  }

  @Test
  void aRestBillerOnTheSoapEndpointIsAnUnknownBiller() throws Exception {
    HttpResponse<String> fetch = soap("FetchBill", fetchBill("DESCO", BILL));
    HttpResponse<String> pay = soap("PayBill", payBill("DESCO", BILL, "667.00", "SOAP-rest"));

    assertThat(fetch.statusCode()).isEqualTo(500);
    assertThat(fetch.body()).contains("<b:code>3004</b:code>");
    assertThat(pay.statusCode()).isEqualTo(500);
    assertThat(pay.body()).contains("<b:code>3004</b:code>");
    assertThat(send("GET", "/billagg/v1/payments/SOAP-rest", null).statusCode()).isEqualTo(404);
  }

  @Test
  void aSoapBillerOnTheRestEndpointsIsAnUnknownBiller() throws Exception {
    HttpResponse<String> fetch = send("POST", "/billagg/v1/bills/fetch",
        "{\"billerCode\":\"KGDCL\",\"billNumber\":\"" + BILL + "\"}");
    HttpResponse<String> pay = send("POST", "/billagg/v1/payments",
        "{\"transactionId\":\"REST-soap\",\"billerCode\":\"BTCL\",\"billNumber\":\"" + BILL + "\",\"amount\":\"667.00\"}");

    assertThat(fetch.statusCode()).isEqualTo(422);
    assertThat(json.readTree(fetch.body()).get("code").asString()).isEqualTo("3004");
    assertThat(pay.statusCode()).isEqualTo(422);
    assertThat(json.readTree(pay.body()).get("code").asString()).isEqualTo("3004");
    assertThat(send("GET", "/billagg/v1/payments/REST-soap", null).statusCode()).isEqualTo(404);
  }

  @Test
  void aWrongOrMissingWsPasswordIsAnAuthenticationFault() throws Exception {
    HttpResponse<String> wrong = soap("FetchBill", security("bits", "not-the-password"), fetchBill("KGDCL", BILL));
    HttpResponse<String> wrongUser = soap("FetchBill", security("someone", PASSWORD), fetchBill("KGDCL", BILL));
    HttpResponse<String> missing = soap("PayBill", null, payBill("KGDCL", BILL, "667.00", "SOAP-noauth"));

    for (HttpResponse<String> res : java.util.List.of(wrong, wrongUser, missing)) {
      assertThat(res.statusCode()).isEqualTo(500);
      assertThat(res.body()).contains("<faultcode>soap:Client</faultcode>", "<b:code>1002</b:code>",
          "authentication failed");
    }
    assertThat(send("GET", "/billagg/v1/payments/SOAP-noauth", null).statusCode()).isEqualTo(404);
  }

  @Test
  void anUnknownOperationOrUnreadableRequestIsAClientFault() throws Exception {
    HttpResponse<String> unknown = soap("Refund", "<b:Refund xmlns:b=\"" + NS + "\"/>");
    HttpRequest garbled = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/billagg/v1/soap"))
        .header("Content-Type", "text/xml").POST(HttpRequest.BodyPublishers.ofString("<soap:Envelope")).build();
    HttpResponse<String> unreadable = http.send(garbled, HttpResponse.BodyHandlers.ofString());

    assertThat(unknown.statusCode()).isEqualTo(500);
    assertThat(unknown.body()).contains("<b:code>1000</b:code>");
    assertThat(unreadable.statusCode()).isEqualTo(500);
    assertThat(unreadable.body()).contains("<b:code>1000</b:code>");
  }

  @Test
  void anOutageIsAServerFaultAndTheWsPasswordIsNotRecorded() throws Exception {
    send("POST", "/sim/billagg/scenario", "{\"outage\":true}");

    HttpResponse<String> res = soap("FetchBill", fetchBill("KGDCL", BILL));

    assertThat(res.statusCode()).isEqualTo(500);
    assertThat(res.body()).contains("<faultcode>soap:Server</faultcode>");
    String recorded = send("GET", "/sim/billagg/requests?path=/soap", null).body();
    assertThat(recorded).contains("FetchBill").doesNotContain(PASSWORD);
  }
}
