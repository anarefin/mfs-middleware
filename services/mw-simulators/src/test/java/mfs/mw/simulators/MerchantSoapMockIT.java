package mfs.mw.simulators;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/**
 * The merchant system SOAP mock {@code POST /merchant-soap/v1/soap} (ticket 115b, {@code urn:merchant:v1}):
 * ValidateMerchant against the seeded merchants, NotifyPayment once per reference, QueryNotify, stateful
 * Refund (once per refundId, never more than notified), WS-Security, and mock control (party
 * {@code merchant-soap}: a 5XX on notify is a soap:Server fault after the notify is kept).
 */
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {"sim.timeout-delay=300ms", "sim.merchant-soap.ws-password=" + MerchantSoapMockIT.PASSWORD})
@Import(TestInfrastructure.class)
class MerchantSoapMockIT {

  static final String PASSWORD = "fake-merchant-soap-ws-password-for-tests";
  static final String NS = "urn:merchant:v1";

  private final HttpClient http = HttpClient.newHttpClient();

  @LocalServerPort int port;

  private HttpResponse<String> soap(String password, String body) throws Exception {
    String envelope = BillAggregatorSoapIT.envelope(BillAggregatorSoapIT.security("bits", password),
        body.replace("<m:", "<m:").replaceFirst("<m:(\\w+)>", "<m:$1 xmlns:m=\"" + NS + "\">"));
    HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/merchant-soap/v1/soap"))
        .header("Content-Type", "text/xml; charset=UTF-8")
        .POST(HttpRequest.BodyPublishers.ofString(envelope))
        .build();
    return http.send(req, HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> soap(String body) throws Exception {
    return soap(PASSWORD, body);
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

  static String validate(String merchant, String till) {
    return "<m:ValidateMerchant><m:merchantId>" + merchant + "</m:merchantId><m:tillId>" + till
        + "</m:tillId></m:ValidateMerchant>";
  }

  static String notify(String merchant, String amount, String ref) {
    return "<m:NotifyPayment><m:merchantId>" + merchant + "</m:merchantId><m:tillId>TILL-01</m:tillId><m:amount>"
        + amount + "</m:amount><m:reference>" + ref + "</m:reference></m:NotifyPayment>";
  }

  static String query(String ref) {
    return "<m:QueryNotify><m:merchantId>M-0001</m:merchantId><m:reference>" + ref + "</m:reference></m:QueryNotify>";
  }

  static String refund(String amount, String ref, String refundId) {
    return "<m:Refund><m:merchantId>M-0001</m:merchantId><m:amount>" + amount + "</m:amount><m:reference>" + ref
        + "</m:reference><m:refundId>" + refundId + "</m:refundId></m:Refund>";
  }

  static String ref() {
    return "BITS" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
  }

  @AfterEach
  void reset() throws Exception {
    send("DELETE", "/mock-control/merchant-soap/journal", null);
  }

  @Test
  void anActiveMerchantWithItsTillIsValid() throws Exception {
    HttpResponse<String> res = soap(validate("M-0001", "TILL-02"));

    assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
    assertThat(res.body()).contains("<m:ValidateMerchantResponse", "<m:status>ACTIVE</m:status>",
        "<m:merchantRef>MREF-M-0001</m:merchantRef>");
  }

  @Test
  void unknownInactiveAndWrongTillAreClientFaultsWithTheirCodes() throws Exception {
    assertThat(soap(validate("M-9999", "TILL-01")).body()).contains("soap:Client", "<m:code>5001</m:code>");
    assertThat(soap(validate("M-0002", "TILL-01")).body()).contains("<m:code>5002</m:code>");
    HttpResponse<String> till = soap(validate("M-0001", "TILL-77"));
    assertThat(till.statusCode()).isEqualTo(500);
    assertThat(till.body()).contains("<m:code>5003</m:code>");
  }

  @Test
  void aWrongPasswordIsRefused() throws Exception {
    HttpResponse<String> res = soap("wrong", validate("M-0001", "TILL-01"));

    assertThat(res.body()).contains("<m:code>1002</m:code>");
  }

  @Test
  void aNotifyIsKeptOncePerReferenceAndFoundByTheQuery() throws Exception {
    String ref = ref();
    assertThat(soap(query(ref)).body()).contains("<m:status>NOT_FOUND</m:status>");

    HttpResponse<String> first = soap(notify("M-0001", "667.00", ref));
    HttpResponse<String> again = soap(notify("M-0001", "667.00", ref));

    assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
    assertThat(first.body()).contains("<m:status>RECEIVED</m:status>", "<m:code>0000</m:code>");
    String notifyRef = first.body().replaceAll("(?s).*<m:notifyRef>([^<]*)</m:notifyRef>.*", "$1");
    assertThat(again.body()).contains("<m:notifyRef>" + notifyRef + "</m:notifyRef>");
    assertThat(soap(query(ref)).body()).contains("<m:status>RECEIVED</m:status>", notifyRef);
  }

  @Test
  void refundsAreStatefulOncePerIdAndNeverAboveTheNotifiedAmount() throws Exception {
    String ref = ref();
    soap(notify("M-0001", "100.00", ref));

    HttpResponse<String> first = soap(refund("60.00", ref, "reverse:a"));
    HttpResponse<String> repeat = soap(refund("60.00", ref, "reverse:a"));
    HttpResponse<String> tooMuch = soap(refund("50.00", ref, "reverse:b"));

    assertThat(first.body()).contains("<m:status>REFUNDED</m:status>");
    String refundRef = first.body().replaceAll("(?s).*<m:refundRef>([^<]*)</m:refundRef>.*", "$1");
    assertThat(repeat.body()).contains("<m:refundRef>" + refundRef + "</m:refundRef>");
    assertThat(tooMuch.body()).contains("soap:Client", "<m:code>5004</m:code>");
    assertThat(soap(refund("10.00", ref(), "reverse:c")).body()).contains("<m:code>5004</m:code>");
  }

  @Test
  void aServerErrorScenarioOnNotifyIsAServerFaultAfterTheNotifyIsKept() throws Exception {
    HttpResponse<String> created = send("POST", "/mock-control/merchant-soap/scenarios",
        "{\"operation\":\"notify\",\"behaviour\":\"HTTP_5XX\",\"nextN\":1}");
    assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
    String ref = ref();

    HttpResponse<String> failed = soap(notify("M-0001", "667.00", ref));
    HttpResponse<String> retried = soap(notify("M-0001", "667.00", ref));

    assertThat(failed.statusCode()).isEqualTo(500);
    assertThat(failed.body()).contains("soap:Server", "<m:code>5000</m:code>");
    assertThat(retried.statusCode()).isEqualTo(200);
    assertThat(retried.body()).contains("<m:status>RECEIVED</m:status>");
    assertThat(send("GET", "/mock-control/merchant-soap/journal", null).body()).contains("/merchant-soap/v1/soap");
  }
}
