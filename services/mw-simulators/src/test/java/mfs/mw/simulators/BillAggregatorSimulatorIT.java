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

/** Every bill aggregator scenario reached over real HTTP through magic values and the control API (ticket 21). */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "sim.timeout-delay=300ms")
@Import(TestInfrastructure.class)
class BillAggregatorSimulatorIT {

  private static final String BILL = "1234567";

  private final HttpClient http = HttpClient.newHttpClient();
  private final JsonMapper json = JsonMapper.builder().build();

  @LocalServerPort int port;

  private HttpResponse<String> send(String method, String path, String body) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    if (body == null) {
      b.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
    }
    return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> fetch(String biller, String bill) throws Exception {
    return send(
        "POST",
        "/billagg/v1/bills/fetch",
        "{\"billerCode\":\"%s\",\"billNumber\":\"%s\"}".formatted(biller, bill));
  }

  private HttpResponse<String> pay(String id, String bill, String amount) throws Exception {
    return send(
        "POST",
        "/billagg/v1/payments",
        "{\"transactionId\":\"%s\",\"billerCode\":\"DESCO\",\"billNumber\":\"%s\",\"amount\":\"%s\"}"
            .formatted(id, bill, amount));
  }

  private JsonNode status(String id) throws Exception {
    return json.readTree(send("GET", "/billagg/v1/payments/" + id, null).body());
  }

  @AfterEach
  void reset() throws Exception {
    send("DELETE", "/sim/billagg/scenario", null);
    send("DELETE", "/sim/billagg/requests", null);
  }

  @Test
  void listsItsBillers() throws Exception {
    HttpResponse<String> res = send("GET", "/billagg/v1/billers", null);
    assertThat(res.statusCode()).isEqualTo(200);
    JsonNode billers = json.readTree(res.body());
    assertThat(billers.findValuesAsString("code")).contains("DESCO", "DPDC", "WASA", "TITAS");
  }

  @Test
  void fetchReturnsTheAmountDueFromTheBillNumber() throws Exception {
    HttpResponse<String> res = fetch("DESCO", BILL);
    assertThat(res.statusCode()).isEqualTo(200);
    JsonNode bill = json.readTree(res.body());
    assertThat(bill.get("status").asString()).isEqualTo("DUE");
    assertThat(bill.get("amount").asString()).isEqualTo("667.00");
    assertThat(bill.get("billNumber").asString()).isEqualTo(BILL);
    assertThat(bill.get("dueDate").asString()).isNotBlank();
  }

  @Test
  void fetchOfAnUnknownBillIsADefiniteRefusal() throws Exception {
    HttpResponse<String> res = fetch("DESCO", "1234404");
    assertThat(res.statusCode()).isEqualTo(422);
    assertThat(json.readTree(res.body()).get("code").asString()).isEqualTo("3001");
  }

  @Test
  void fetchOfAPaidBillIsRefused() throws Exception {
    HttpResponse<String> res = fetch("DESCO", "1234000");
    assertThat(res.statusCode()).isEqualTo(422);
    assertThat(json.readTree(res.body()).get("code").asString()).isEqualTo("3002");
  }

  @Test
  void fetchForAnUnknownBillerIsRefused() throws Exception {
    HttpResponse<String> res = fetch("NOPE", BILL);
    assertThat(res.statusCode()).isEqualTo(422);
    assertThat(json.readTree(res.body()).get("code").asString()).isEqualTo("3004");
  }

  @Test
  void paySuccessAndInquiry() throws Exception {
    HttpResponse<String> res = pay("BP-ok", BILL, "667.00");
    assertThat(res.statusCode()).isEqualTo(200);
    JsonNode body = json.readTree(res.body());
    assertThat(body.get("code").asString()).isEqualTo("000");
    assertThat(body.get("paymentRef").asString()).startsWith("AGG-");
    assertThat(status("BP-ok").get("status").asString()).isEqualTo("SUCCESS");
  }

  @Test
  void payRefusedByTheBillerByBillNumberSuffix() throws Exception {
    HttpResponse<String> res = pay("BP-bf", "1234999", "100.00");
    assertThat(res.statusCode()).isEqualTo(422);
    assertThat(json.readTree(res.body()).get("code").asString()).isEqualTo("3003");
    assertThat(status("BP-bf").get("status").asString()).isEqualTo("FAILED");
  }

  @Test
  void payTimeoutAppliesThePaymentAndInquiryShowsIt() throws Exception {
    long t0 = System.nanoTime();
    HttpResponse<String> res = pay("BP-to", BILL, "100.13");
    assertThat((System.nanoTime() - t0) / 1_000_000).isGreaterThanOrEqualTo(250);
    assertThat(res.statusCode()).isEqualTo(200);
    assertThat(status("BP-to").get("status").asString()).isEqualTo("SUCCESS");
  }

  @Test
  void payServerErrorAfterSend() throws Exception {
    assertThat(pay("BP-5xx", BILL, "100.77").statusCode()).isEqualTo(500);
    assertThat(status("BP-5xx").get("status").asString()).isEqualTo("SUCCESS");
  }

  @Test
  void payUnknownCode() throws Exception {
    HttpResponse<String> res = pay("BP-unk", BILL, "100.99");
    assertThat(res.statusCode()).isEqualTo(422);
    assertThat(json.readTree(res.body()).get("code").asString()).isEqualTo("9876");
  }

  @Test
  void payMalformedReply() throws Exception {
    HttpResponse<String> res = pay("BP-bad", BILL, "100.55");
    assertThat(res.statusCode()).isEqualTo(200);
    org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () -> json.readTree(res.body()));
    assertThat(status("BP-bad").get("status").asString()).isEqualTo("SUCCESS");
  }

  @Test
  void repeatedPaymentReturnsTheOriginalOutcome() throws Exception {
    pay("BP-rep", "1234999", "100.00");
    HttpResponse<String> again = pay("BP-rep", BILL, "100.00");
    assertThat(again.statusCode()).isEqualTo(422);
    assertThat(json.readTree(again.body()).get("code").asString()).isEqualTo("3003");
  }

  @Test
  void statusOfAnUnknownPaymentIs404NotFound() throws Exception {
    HttpResponse<String> res = send("GET", "/billagg/v1/payments/BP-never", null);
    assertThat(res.statusCode()).isEqualTo(404);
    assertThat(json.readTree(res.body()).get("status").asString()).isEqualTo("NOT_FOUND");
  }

  @Test
  void outageAnswers503WithoutApplying() throws Exception {
    send("POST", "/sim/billagg/scenario", "{\"outage\":true}");
    assertThat(pay("BP-out", BILL, "100.00").statusCode()).isEqualTo(503);
    assertThat(fetch("DESCO", BILL).statusCode()).isEqualTo(503);
    assertThat(send("GET", "/billagg/v1/payments/BP-out", null).statusCode()).isEqualTo(503);
    send("DELETE", "/sim/billagg/scenario", null);
    assertThat(send("GET", "/billagg/v1/payments/BP-out", null).statusCode()).isEqualTo(404);
  }

  @Test
  void forcedModeAndForcedTimeoutDelay() throws Exception {
    send("POST", "/sim/billagg/scenario", "{\"forceMode\":\"BUSINESS_FAILURE\"}");
    assertThat(pay("BP-force", BILL, "100.00").statusCode()).isEqualTo(422);
    send("POST", "/sim/billagg/scenario", "{\"forceMode\":\"TIMEOUT\",\"timeoutDelayMs\":10}");
    assertThat(pay("BP-force-to", BILL, "100.00").statusCode()).isEqualTo(200);
  }

  @Test
  void invalidBodiesAre400() throws Exception {
    assertThat(pay("BP-inv", BILL, "abc").statusCode()).isEqualTo(400);
    assertThat(fetch("DESCO", "").statusCode()).isEqualTo(400);
  }

  @Test
  void requestsAreRecordedPerPartner() throws Exception {
    fetch("DESCO", BILL);
    pay("BP-rec", BILL, "100.00");
    JsonNode all = json.readTree(send("GET", "/sim/billagg/requests", null).body());
    assertThat(all).hasSize(2);
    assertThat(all.get(1).get("body").asString()).contains("BP-rec");
    assertThat(json.readTree(send("GET", "/sim/billagg/requests?path=/bills/fetch", null).body())).hasSize(1);
    assertThat(json.readTree(send("GET", "/sim/ocs/requests", null).body())).isEmpty();
  }
}
