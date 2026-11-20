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
 * The simulated bank gateway (ticket 117d, FRS EXT-12, MCK-02/03/08): every bank of the generated
 * {@code seed/v1/bank-profiles.csv} at {@code /bank/<code>/v1} over its protocol (REST or SOAP 1.1) and
 * auth, seeded accounts, the account-ending triggers, stateful transfers and reversals, mock party {@code
 * bank-<code>}, signed callbacks.
 */
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {"sim.timeout-delay=300ms", "sim.callback-secrets.bank-dbbl=fake-bank-dbbl-hmac-secret"})
@Import(TestInfrastructure.class)
class BankGatewaySimulatorIT {

  static final String WSSE = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd";
  static final Map<String, String> HMAC = Map.of("X-Signature", "c2ln", "X-Timestamp", "1700000000");
  static final Map<String, String> BASIC =
      Map.of("Authorization", "Basic " + Base64.getEncoder().encodeToString("bits:pw".getBytes(StandardCharsets.UTF_8)));

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
    for (String party : List.of("bank-dbbl", "bank-brac", "bank-sonali", "bank-janata")) {
      send("DELETE", "/mock-control/" + party + "/journal", null, Map.of());
      send("DELETE", "/mock-control/" + party + "/scenarios", null, Map.of());
    }
  }

  @Test
  void everyBankOfTheCsvIsAMockParty() throws Exception {
    JsonNode parties = json.readTree(send("GET", "/mock-control/parties", null, Map.of()).body());
    assertThat(parties.findValuesAsString("name")).contains(
        "bank-dbbl", "bank-brac", "bank-standard", "bank-sonali", "bank-janata", "bank-nrbc");
    assertThat(send("GET", "/bank/dbbl/v1/health", null, HMAC).body()).contains("\"UP\"");
    assertThat(send("GET", "/bank/janata/v1/health", null, Map.of()).statusCode()).isEqualTo(200);
    assertThat(send("GET", "/bank/nosuch/v1/health", null, Map.of()).statusCode()).isEqualTo(404);
  }

  @Test
  void aRestBankValidatesSeededAccountsAndRefusesByTrigger() throws Exception {
    HttpResponse<String> ok = post("/bank/dbbl/v1/accounts/verify", "{\"accountNumber\":\"1234567890\"}", HMAC);
    assertThat(ok.statusCode()).as(ok.body()).isEqualTo(200);
    JsonNode body = json.readTree(ok.body());
    assertThat(body.get("status").asString()).isEqualTo("ACTIVE");
    assertThat(body.get("accountName").asString()).isEqualTo("Rahim Uddin");
    assertThat(body.get("verifyRef").asString()).startsWith("DBBL-AVL-");

    assertThat(code(post("/bank/dbbl/v1/accounts/verify", "{\"accountNumber\":\"5555555500\"}", HMAC), 422))
        .isEqualTo("4001");
    assertThat(code(post("/bank/dbbl/v1/accounts/verify", "{\"accountNumber\":\"1234567893\"}", HMAC), 422))
        .as("seeded DORMANT").isEqualTo("4002");
    // the profile's auth is required: hmac-sha256 for dbbl, an api key for brac
    assertThat(post("/bank/dbbl/v1/accounts/verify", "{\"accountNumber\":\"1234567890\"}", Map.of()).statusCode())
        .isEqualTo(401);
    assertThat(post("/bank/brac/v1/accounts/verify", "{\"accountNumber\":\"1234567890\"}", HMAC).statusCode())
        .isEqualTo(401);
    assertThat(post("/bank/brac/v1/accounts/verify", "{\"accountNumber\":\"1234567890\"}",
        Map.of("X-Api-Key", "k")).statusCode()).isEqualTo(200);
  }

  @Test
  void aRestCreditIsStatefulAndReversedOnce() throws Exception {
    String id = id();
    HttpResponse<String> res = transfer("dbbl", id, "2001000101", "1500.00", "CREDIT");
    assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
    JsonNode t = json.readTree(res.body());
    assertThat(t.get("status").asString()).isEqualTo("SUCCESS");
    assertThat(t.get("code").asString()).isEqualTo("000");
    String bankRef = t.get("bankRef").asString();
    // a repeat is not applied twice
    assertThat(json.readTree(transfer("dbbl", id, "2001000101", "1500.00", "CREDIT").body()).get("bankRef").asString())
        .isEqualTo(bankRef);
    assertThat(balance("dbbl", "2001000101")).isEqualTo("751500.00");

    JsonNode status = json.readTree(send("GET", "/bank/dbbl/v1/transfers/" + id, null, HMAC).body());
    assertThat(status.get("status").asString()).isEqualTo("SUCCESS");
    assertThat(status.get("bankRef").asString()).isEqualTo(bankRef);

    HttpResponse<String> rev = post("/bank/dbbl/v1/transfers/" + id + "/reverse",
        "{\"reversalId\":\"reverse:" + id + "\",\"amount\":\"1500.00\"}", HMAC);
    assertThat(rev.statusCode()).as(rev.body()).isEqualTo(200);
    String reversalRef = json.readTree(rev.body()).get("reversalRef").asString();
    assertThat(json.readTree(rev.body()).get("status").asString()).isEqualTo("REVERSED");
    assertThat(json.readTree(post("/bank/dbbl/v1/transfers/" + id + "/reverse", "{}", HMAC).body())
        .get("reversalRef").asString()).isEqualTo(reversalRef);
    assertThat(balance("dbbl", "2001000101")).isEqualTo("750000.00");

    HttpResponse<String> unknown = send("GET", "/bank/dbbl/v1/transfers/" + id(), null, HMAC);
    assertThat(unknown.statusCode()).isEqualTo(404);
    assertThat(json.readTree(unknown.body()).get("status").asString()).isEqualTo("NOT_FOUND");
    assertThat(code(post("/bank/dbbl/v1/transfers/" + id() + "/reverse", "{}", HMAC), 404)).isEqualTo("4040");
  }

  @Test
  void debitTriggersAndTheBalance() throws Exception {
    assertThat(code(transfer("dbbl", id(), "1234567892", "500.00", "DEBIT"), 422)).as("balance 100").isEqualTo("4003");
    assertThat(code(transfer("dbbl", id(), "7777777799", "5.00", "DEBIT"), 422)).isEqualTo("4003");
    assertThat(code(transfer("dbbl", id(), "7777777797", "5.00", "CREDIT"), 422)).isEqualTo("4004");
    assertThat(code(transfer("dbbl", id(), "7777777796", "5.00", "CREDIT"), 422)).isEqualTo("4005");
    String failed = id();
    assertThat(code(transfer("dbbl", failed, "7777777700", "5.00", "CREDIT"), 422)).isEqualTo("4001");
    JsonNode s = json.readTree(send("GET", "/bank/dbbl/v1/transfers/" + failed, null, HMAC).body());
    assertThat(s.get("status").asString()).isEqualTo("FAILED");
    assertThat(s.get("code").asString()).isEqualTo("4001");
    assertThat(code(transfer("dbbl", id(), "7777777712", "5.99", "CREDIT"), 422)).isEqualTo("9876");
  }

  @Test
  void aPendingDebitSettlesByASignedCallback() throws Exception {
    assertThat(send("PUT", "/mock-control/bank-dbbl/settings",
        "{\"callbackUrl\":\"http://127.0.0.1:%d/partner/bank-dbbl/callback/transfers\"}"
            .formatted(middleware.getAddress().getPort()), Map.of()).statusCode()).isEqualTo(200);
    String id = id();

    HttpResponse<String> res = transfer("dbbl", id, "1234567890", "500.00", "DEBIT");

    assertThat(res.statusCode()).as(res.body()).isEqualTo(202);
    assertThat(json.readTree(res.body()).get("status").asString()).isEqualTo("PENDING");
    long end = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (received.isEmpty() && System.nanoTime() < end) {
      Thread.sleep(20);
    }
    assertThat(received).hasSize(1);
    Received cb = received.getFirst();
    assertThat(cb.path()).isEqualTo("/partner/bank-dbbl/callback/transfers");
    assertThat(cb.signature()).isNotBlank();
    JsonNode body = json.readTree(cb.body());
    assertThat(body.get("transactionId").asString()).isEqualTo(id);
    assertThat(body.get("status").asString()).isEqualTo("SUCCESS");
    assertThat(balance("dbbl", "1234567890")).isEqualTo("499500.00");
  }

  @Test
  void aScenarioOnTheBankPartyAndItsJournal() throws Exception {
    assertThat(send("POST", "/mock-control/bank-dbbl/scenarios",
        "{\"operation\":\"transfer\",\"behaviour\":\"BUSINESS_ERROR\",\"code\":\"4005\",\"nextN\":1}", Map.of())
        .statusCode()).isEqualTo(201);
    assertThat(code(transfer("dbbl", id(), "1234567890", "5.00", "CREDIT"), 422)).isEqualTo("4005");
    assertThat(transfer("dbbl", id(), "1234567890", "5.00", "CREDIT").statusCode()).isEqualTo(200);
    JsonNode journal = json.readTree(send("GET", "/mock-control/bank-dbbl/journal", null, Map.of()).body());
    assertThat(journal).hasSize(2);
    assertThat(send("PUT", "/mock-control/bank-dbbl/switch", "{\"state\":\"MAINTENANCE\"}", Map.of()).statusCode())
        .isEqualTo(200);
    assertThat(transfer("dbbl", id(), "1234567890", "5.00", "CREDIT").statusCode()).isEqualTo(503);
  }

  @Test
  void aSoapBankValidatesTransfersAndFaults() throws Exception {
    HttpResponse<String> avl = soap("sonali", BASIC, null,
        "<b:ValidateAccount xmlns:b=\"urn:bank:sonali:v1\"><b:accountNumber>3001000101</b:accountNumber></b:ValidateAccount>");
    assertThat(avl.statusCode()).as(avl.body()).isEqualTo(200);
    assertThat(avl.body()).contains("<status>ACTIVE</status>", "<accountName>Mizanur Rahman</accountName>",
        "ValidateAccountResponse", "urn:bank:sonali:v1");

    String id = id();
    String security = "<wsse:Security xmlns:wsse=\"" + WSSE + "\"><wsse:UsernameToken><wsse:Username>bits</wsse:Username>"
        + "<wsse:Password>pw</wsse:Password></wsse:UsernameToken></wsse:Security>";
    HttpResponse<String> tr = soap("janata", Map.of(), security,
        "<Transfer xmlns=\"urn:bank:janata:v1\"><transactionId>" + id + "</transactionId><accountNumber>1234567890"
            + "</accountNumber><amount>1500.00</amount><direction>CREDIT</direction></Transfer>");
    assertThat(tr.statusCode()).as(tr.body()).isEqualTo(200);
    assertThat(tr.body()).contains("<TransferResponse", "<status>SUCCESS</status>", "<code>000</code>");
    HttpResponse<String> st = soap("janata", Map.of(), security,
        "<TransferStatus xmlns=\"urn:bank:janata:v1\"><transactionId>" + id + "</transactionId></TransferStatus>");
    assertThat(st.body()).contains("<TransferStatusResponse", "<status>SUCCESS</status>");
    HttpResponse<String> rv = soap("janata", Map.of(), security,
        "<ReverseTransfer xmlns=\"urn:bank:janata:v1\"><transactionId>" + id + "</transactionId><reversalId>r1"
            + "</reversalId><amount>1500.00</amount></ReverseTransfer>");
    assertThat(rv.body()).contains("<ReverseTransferResponse", "<status>REVERSED</status>", "<reversalRef>");
    HttpResponse<String> nf = soap("janata", Map.of(), security,
        "<TransferStatus xmlns=\"urn:bank:janata:v1\"><transactionId>" + id() + "</transactionId></TransferStatus>");
    assertThat(nf.body()).contains("<status>NOT_FOUND</status>");

    HttpResponse<String> dormant = soap("sonali", BASIC, null,
        "<ValidateAccount xmlns=\"urn:bank:sonali:v1\"><accountNumber>1234567893</accountNumber></ValidateAccount>");
    assertThat(dormant.statusCode()).isEqualTo(500);
    assertThat(dormant.body()).contains("soap:Client", ">4002<");
    // the profile's auth: Basic for sonali, WS-Security for janata
    HttpResponse<String> noAuth = soap("janata", Map.of(), null,
        "<ValidateAccount xmlns=\"urn:bank:janata:v1\"><accountNumber>1234567890</accountNumber></ValidateAccount>");
    assertThat(noAuth.body()).contains("soap:Client", ">1002<");
    assertThat(soap("sonali", Map.of(), security,
        "<ValidateAccount xmlns=\"urn:bank:sonali:v1\"><accountNumber>1234567890</accountNumber></ValidateAccount>")
        .body()).contains(">1002<");
    // the protocol guard: a SOAP bank has no REST endpoints
    assertThat(post("/bank/sonali/v1/accounts/verify", "{\"accountNumber\":\"1234567890\"}", BASIC).statusCode())
        .isEqualTo(404);
    JsonNode journal = json.readTree(send("GET", "/mock-control/bank-janata/journal", null, Map.of()).body());
    assertThat(journal.toString()).doesNotContain(">pw<");
  }

  private String balance(String bank, String account) throws Exception {
    return json.readTree(send("GET", "/sim/bank/" + bank + "/accounts/" + account, null, Map.of()).body())
        .get("balance").asString();
  }

  private HttpResponse<String> transfer(String bank, String id, String account, String amount, String direction)
      throws Exception {
    return post("/bank/" + bank + "/v1/transfers", "{\"transactionId\":\"%s\",\"accountNumber\":\"%s\",\"amount\":\"%s\",\"direction\":\"%s\"}"
        .formatted(id, account, amount, direction), HMAC);
  }

  private String code(HttpResponse<String> res, int status) throws Exception {
    assertThat(res.statusCode()).as(res.body()).isEqualTo(status);
    return json.readTree(res.body()).get("code").asString();
  }

  private HttpResponse<String> post(String path, String body, Map<String, String> headers) throws Exception {
    return send("POST", path, body, headers);
  }

  private HttpResponse<String> soap(String bank, Map<String, String> headers, String header, String body)
      throws Exception {
    String env = "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">"
        + (header == null ? "" : "<soap:Header>" + header + "</soap:Header>") + "<soap:Body>" + body
        + "</soap:Body></soap:Envelope>";
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/bank/" + bank + "/v1/soap"))
        .header("Content-Type", "text/xml").POST(HttpRequest.BodyPublishers.ofString(env));
    headers.forEach(b::header);
    return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
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

  private static String id() {
    return "BITS" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
  }
}
