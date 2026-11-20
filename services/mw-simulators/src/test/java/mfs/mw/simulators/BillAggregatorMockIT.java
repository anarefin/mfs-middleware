package mfs.mw.simulators;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The bill aggregator mocks (ticket 114, FRS MCK-02/06/08): four profiles in their own dialect and auth,
 * seeded stateful bills (unpaid -> paid -> reversed, back to the seed only by the party reset), a second
 * PAY of a paid bill refused, an idempotent reverse, and the payment callback sent twice on
 * DUPLICATE_CALLBACK.
 */
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {"sim.timeout-delay=300ms", "sim.callback-secrets.sslcommerz=fake-sslcommerz-hmac-secret"})
@Import(TestInfrastructure.class)
class BillAggregatorMockIT {

  static final Map<String, String> HMAC = Map.of("X-Key-Id", "bits-store", "X-Signature", "c2ln", "X-Timestamp", "1700000000");
  static final Map<String, String> BASIC =
      Map.of("Authorization", "Basic " + Base64.getEncoder().encodeToString("bits:pw".getBytes(StandardCharsets.UTF_8)));
  static final String WSSE_HEADER = "<wsse:Security xmlns:wsse=\"http://docs.oasis-open.org/wss/2004/01/"
      + "oasis-200401-wss-wssecurity-secext-1.0.xsd\"><wsse:UsernameToken><wsse:Username>bits</wsse:Username>"
      + "<wsse:Password>pw</wsse:Password></wsse:UsernameToken></wsse:Security>";

  record Received(String path, String signature, String body) {}

  private final HttpClient http = HttpClient.newHttpClient();
  private final JsonMapper json = JsonMapper.builder().build();
  private final List<Received> received = new CopyOnWriteArrayList<>();
  private HttpServer middleware;

  @LocalServerPort int port;

  @BeforeEach
  void startMiddleware() throws Exception {
    middleware = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    middleware.createContext("/", ex -> {
      received.add(new Received(ex.getRequestURI().getRawPath(), ex.getRequestHeaders().getFirst("X-Signature"),
          new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
      ex.sendResponseHeaders(200, -1);
      ex.close();
    });
    middleware.start();
  }

  @AfterEach
  void stop() throws Exception {
    middleware.stop(0);
    for (String party : List.of("sslcommerz", "ekpay", "agg3", "agg4")) {
      send("DELETE", "/mock-control/" + party + "/journal", null, Map.of());
      send("DELETE", "/mock-control/" + party + "/scenarios", null, Map.of());
    }
  }

  @Test
  void theFourAggregatorsAreMockParties() throws Exception {
    JsonNode parties = json.readTree(send("GET", "/mock-control/parties", null, Map.of()).body());
    assertThat(parties.findValuesAsString("name")).contains("sslcommerz", "ekpay", "agg3", "agg4");
  }

  @Test
  void aPaidBillStaysPaidASecondPayIsRefusedAndAReverseIsIdempotent() throws Exception {
    String bill = "{\"biller_code\":\"AMBERIT\",\"bill_no\":\"1140000001\"}";
    JsonNode due = json.readTree(post("/sslcommerz/v1/bills/inquiry", bill, HMAC).body());
    assertThat(due.get("status").asString()).isEqualTo("UNPAID");
    assertThat(due.get("amount").asString()).isEqualTo("1250.50");
    assertThat(post("/sslcommerz/v1/bills/inquiry", bill, Map.of()).statusCode()).isEqualTo(401);

    String ref = ref();
    HttpResponse<String> paid = pay(ref, "1140000001", "1250.50");
    assertThat(paid.statusCode()).as(paid.body()).isEqualTo(200);
    assertThat(json.readTree(paid.body()).get("status").asString()).isEqualTo("VALID");
    // the same reference again answers the stored payment
    assertThat(json.readTree(pay(ref, "1140000001", "1250.50").body()).get("val_id").asString())
        .isEqualTo(json.readTree(paid.body()).get("val_id").asString());

    assertThat(json.readTree(post("/sslcommerz/v1/bills/inquiry", bill, HMAC).body()).get("status").asString())
        .isEqualTo("VALID");
    HttpResponse<String> second = pay(ref(), "1140000001", "1250.50");
    assertThat(second.statusCode()).isEqualTo(422);
    assertThat(json.readTree(second.body()).get("error_code").asString()).isEqualTo("E102");

    HttpResponse<String> rev = post("/sslcommerz/v1/payments/" + ref + "/reverse", "{}", HMAC);
    assertThat(rev.statusCode()).as(rev.body()).isEqualTo(200);
    String refundRef = json.readTree(rev.body()).get("refund_ref_id").asString();
    assertThat(json.readTree(post("/sslcommerz/v1/payments/" + ref + "/reverse", "{}", HMAC).body())
        .get("refund_ref_id").asString()).isEqualTo(refundRef);
    assertThat(json.readTree(send("GET", "/sim/agg/sslcommerz/bills/AMBERIT/1140000001", null, Map.of()).body())
        .get("status").asString()).isEqualTo("REVERSED");

    // the party reset puts the seed back
    send("DELETE", "/mock-control/sslcommerz/journal", null, Map.of());
    assertThat(json.readTree(send("GET", "/sim/agg/sslcommerz/bills/AMBERIT/1140000001", null, Map.of()).body())
        .get("status").asString()).isEqualTo("UNPAID");
    assertThat(json.readTree(post("/sslcommerz/v1/bills/inquiry", "{\"biller_code\":\"AMBERIT\",\"bill_no\":\"1140000003\"}",
        HMAC).body()).get("status").asString()).as("seeded PAID").isEqualTo("VALID");
  }

  @Test
  void refusalsComeInTheProfilesCodes() throws Exception {
    assertThat(code(post("/sslcommerz/v1/bills/inquiry", "{\"biller_code\":\"AMBERIT\",\"bill_no\":\"404\"}", HMAC)))
        .isEqualTo("E101");
    assertThat(code(post("/sslcommerz/v1/bills/inquiry", "{\"biller_code\":\"NOPE\",\"bill_no\":\"1\"}", HMAC)))
        .isEqualTo("E104");
    assertThat(code(pay(ref(), "1140000002", "1.00"))).isEqualTo("E106");
  }

  @Test
  void ekpayIssuesATokenAndWantsIt() throws Exception {
    String body = "{\"billerId\":\"NESCO\",\"billNo\":\"1140000001\"}";
    assertThat(post("/ekpay/v1/bills/inquiry", body, Map.of("Authorization", "Bearer nope")).statusCode()).isEqualTo(401);
    String token = json.readTree(send("POST", "/ekpay/v1/oauth/token", null, BASIC).body()).get("access_token").asString();
    JsonNode due = json.readTree(post("/ekpay/v1/bills/inquiry", body, Map.of("Authorization", "Bearer " + token)).body());
    assertThat(due.get("status").asString()).isEqualTo("DUE");
    assertThat(due.get("billMonth").asString()).isEqualTo("2026-11");
  }

  @Test
  void agg4AnswersInsideItsEnvelope() throws Exception {
    String ref = ref();
    HttpResponse<String> res = post("/agg4/v1/payments",
        "{\"req\":{\"ref\":\"" + ref + "\",\"svc\":\"METLIFE\",\"acct\":\"1140000002\",\"amt\":\"480.00\"}}", BASIC);
    assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
    assertThat(json.readTree(res.body()).get("res").get("state").asString()).isEqualTo("DONE");
    assertThat(json.readTree(send("GET", "/agg4/v1/payments/" + ref, null, BASIC).body()).get("res").get("rcpt").asString())
        .startsWith("AGG4-");
  }

  @Test
  void agg3SpeaksSoapWithWsSecurity() throws Exception {
    HttpResponse<String> bill = soap(WSSE_HEADER,
        "<GetBill xmlns=\"urn:agg3:v1\"><billerCode>DU</billerCode><billNumber>1140000001</billNumber></GetBill>");
    assertThat(bill.statusCode()).as(bill.body()).isEqualTo(200);
    assertThat(bill.body()).contains("<status>UNPAID</status>").contains("<amountDue>1250.50</amountDue>");
    String ref = ref();
    String payBody = "<PayBill xmlns=\"urn:agg3:v1\"><transactionId>" + ref + "</transactionId><billerCode>DU</billerCode>"
        + "<billNumber>1140000001</billNumber><amount>1250.50</amount></PayBill>";
    assertThat(soap(WSSE_HEADER, payBody).body()).contains("<status>PAID</status>").contains("<receiptNo>");
    HttpResponse<String> again = soap(WSSE_HEADER, payBody.replace(ref, ref()));
    assertThat(again.statusCode()).isEqualTo(500);
    assertThat(again.body()).contains("A3-02");
    assertThat(soap(null, payBody).body()).contains("1002");
  }

  @Test
  void aDuplicateCallbackScenarioSendsThePaymentTwiceSigned() throws Exception {
    assertThat(send("PUT", "/mock-control/sslcommerz/settings",
        "{\"callbackUrl\":\"http://127.0.0.1:%d/partner/sslcommerz/callback/payments\"}"
            .formatted(middleware.getAddress().getPort()), Map.of()).statusCode()).isEqualTo(200);
    assertThat(send("POST", "/mock-control/sslcommerz/scenarios",
        "{\"operation\":\"pay\",\"behaviour\":\"DUPLICATE_CALLBACK\"}", Map.of()).statusCode()).isEqualTo(201);
    String ref = ref();
    assertThat(pay(ref, "1140000012", "75.25").statusCode()).isEqualTo(200);
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (received.size() < 2 && System.nanoTime() < deadline) {
      Thread.sleep(50);
    }
    assertThat(received).hasSize(2);
    assertThat(received.get(0).body()).contains("\"tran_id\":\"" + ref + "\"").contains("\"status\":\"VALID\"");
    assertThat(received.get(0).signature()).isNotBlank();
  }

  private HttpResponse<String> pay(String ref, String billNo, String amount) throws Exception {
    return post("/sslcommerz/v1/payments", "{\"tran_id\":\"" + ref + "\",\"biller_code\":\"" + biller(billNo)
        + "\",\"bill_no\":\"" + billNo + "\",\"amount\":\"" + amount + "\"}", HMAC);
  }

  private static String biller(String billNo) {
    return billNo.startsWith("114000001") ? "CARNIVAL" : "AMBERIT";
  }

  private String code(HttpResponse<String> res) throws Exception {
    assertThat(res.statusCode()).as(res.body()).isEqualTo(422);
    return json.readTree(res.body()).get("error_code").asString();
  }

  private HttpResponse<String> post(String path, String body, Map<String, String> headers) throws Exception {
    return send("POST", path, body, headers);
  }

  private HttpResponse<String> soap(String header, String body) throws Exception {
    String env = "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">"
        + (header == null ? "" : "<soap:Header>" + header + "</soap:Header>") + "<soap:Body>" + body
        + "</soap:Body></soap:Envelope>";
    return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/agg3/v1/soap"))
        .header("Content-Type", "text/xml").POST(HttpRequest.BodyPublishers.ofString(env)).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> send(String method, String path, String body, Map<String, String> headers)
      throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    headers.forEach(b::header);
    if (body == null) {
      b.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
    }
    return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static String ref() {
    return "BITS" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
  }
}
