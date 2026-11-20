package mfs.mw.ussd.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.Schema;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import mfs.mw.ussd.client.BillPayClient;
import mfs.mw.ussd.config.BillPayProperties;
import mfs.mw.ussd.dto.Amount;
import mfs.mw.ussd.dto.Bill;
import mfs.mw.ussd.dto.Biller;
import mfs.mw.ussd.dto.ErrorDetail;
import mfs.mw.ussd.dto.EncryptedPin;
import mfs.mw.ussd.dto.ErrorEnvelope;
import mfs.mw.ussd.dto.HttpUssdResponse;
import mfs.mw.ussd.dto.PinKey;
import mfs.mw.ussd.dto.TxnAuthorisation;
import mfs.mw.ussd.dto.TxnAuthorisationRequest;
import mfs.mw.ussd.dto.TxnRequest;
import mfs.mw.ussd.dto.UssdSession;
import mfs.mw.ussd.dto.FlowRequest;
import mfs.mw.ussd.mapper.TxnRequestMapper;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * API contracts of {@code contracts.yml}: the response bodies of the MNO HTTP USSD API this module
 * serves ({@code api/mw-ussd/ussd-http.v1.openapi.yaml}), serialised with the service's JsonMapper
 * (as Spring MVC writes them), validate against the OpenAPI component schemas; the requests
 * {@code BillPayClient} sends to mw-adapter-billpay ({@code api/mw-adapter-billpay/billpay.v1.openapi.yaml})
 * validate against that API, and contract-shaped answers deserialise into its DTOs. No Spring
 * context, no Docker: the adapter is a JDK {@link HttpServer} on a loopback port.
 */
class ApiContractsTest {

  static final JsonMapper JSON = ServiceJson.MAPPER;
  static final String USSD_API = "api/mw-ussd/ussd-http.v1.openapi.yaml";
  static final String BILLPAY_API = "api/mw-adapter-billpay/billpay.v1.openapi.yaml";
  static final String AUTH_API = "api/mw-auth/auth-internal.v1.openapi.yaml";
  static final String CORE_API = "api/mw-core-mock/core-wallet.v1.openapi.yaml";
  static final String CORRELATION_ID = "019a4f6e-0000-7a3b-8c4d-5e6f7a8b9c0d";

  @Test
  void theManifestListsTheseApis() {
    ContractManifest manifest = ContractManifest.load();
    assertThat(manifest.producedApis()).containsExactly(USSD_API);
    assertThat(manifest.consumedApis()).containsExactly(BILLPAY_API, AUTH_API, CORE_API);
  }

  // ---- served: ussd-http.v1 ----

  static void assertValid(Schema schema, Object body) {
    JsonNode tree = JSON.readTree(JSON.writeValueAsString(body));
    assertThat(schema.validate(tree)).as("%s", tree).isEmpty();
  }

  @Test
  void screensMatchTheContract() {
    // HttpUssdTransport.handle: the next screen with the MNO's continue or end flag
    Schema screen = OpenApiSchemas.at(USSD_API, "paths", "/ussd/{mno}", "post", "responses", "200", "content",
        "application/json", "schema");
    assertValid(screen, new HttpUssdResponse("mno-sess-1", "1. Send money\n2. Cash out", "CON"));
    assertValid(screen, new HttpUssdResponse("mno-sess-1", "", "END"));
    assertValid(OpenApiSchemas.component(USSD_API, "UssdScreen"),
        new HttpUssdResponse("mno-sess-1", "Thank you", "END"));
  }

  @Test
  void errorEnvelopesMatchTheContract() {
    Schema schema = OpenApiSchemas.component(USSD_API, "ErrorEnvelope");
    assertValid(schema, new ErrorEnvelope("MW-VAL-001", "Invalid request", null, null, CORRELATION_ID, false,
        List.of(new ErrorDetail("msisdn", "not a Bangladeshi mobile number"))));
    assertValid(schema, new ErrorEnvelope("MW-VAL-003", "Unknown MNO", null, null, CORRELATION_ID, false, null));
  }

  // ---- called: mw-auth (AuthClient, ticket 72) ----

  @Test
  void theAuthorisationRequestTheMapperBuildsMatchesTheContract() {
    Instant now = Instant.parse("2026-11-15T10:00:00Z");
    UssdSession s = new UssdSession("robi", "sess-1", "01712345678", "main", 2, "en", "x.send", List.of(), Map.of(), 0,
        List.of(), false, now);
    TxnRequestMapper mapper = new TxnRequestMapper();
    EncryptedPin pin = new EncryptedPin("PIN_TRANSPORT.v1", "Y2lwaGVy");
    for (FlowRequest f : List.of(
        new FlowRequest("P2P", s, "p", Map.of("creditParty", "01812345678", "amount", "500.50"), pin),
        new FlowRequest("BILL_PAY", s, "p",
            Map.of("creditParty", "1234567", "amount", "667", "partnerId", "billagg", "billerCode", "DESCO"), pin),
        new FlowRequest("BALANCE", s, "p", Map.of(), pin))) {
      TxnRequest req = mapper.toRequest(f, "019a4f6e-1c2d-7a3b-8c4d-5e6f7a8b9c0d", "k".repeat(64), CORRELATION_ID, now);
      TxnAuthorisationRequest body = mapper.authorisation(req, pin);
      assertValid(OpenApiSchemas.at(AUTH_API, "paths", "/internal/v1/txn-authorisations", "post", "requestBody",
          "content", "application/json", "schema"), body);
    }
  }

  @Test
  void contractShapedMwAuthAnswersDeserialise() {
    String authorised = "{\"assertion\":\"eyJhbGciOiJFUzI1NiJ9.eyJqdGkiOiJ0In0.c2lnbmF0dXJl\","
        + "\"expiresAt\":\"2026-11-15T10:01:00Z\",\"newDevice\":false}";
    assertThat(OpenApiSchemas.component(AUTH_API, "TxnAuthorisation").validate(JSON.readTree(authorised))).isEmpty();
    assertThat(JSON.readValue(authorised, TxnAuthorisation.class).assertion()).startsWith("eyJ");

    String key = "{\"keyId\":\"PIN_TRANSPORT.v1\",\"algorithm\":\"RSA-OAEP-SHA-256\",\"publicKeyPem\":\"-----BEGIN PUBLIC KEY-----\","
        + "\"purpose\":\"PIN_TRANSPORT\"}";
    assertThat(OpenApiSchemas.at(AUTH_API, "paths", "/internal/v1/pin-key", "get", "responses", "200", "content",
        "application/json", "schema").validate(JSON.readTree(key))).isEmpty();
    mfs.mw.ussd.dto.ChannelPinKey read = JSON.readValue(key, mfs.mw.ussd.dto.ChannelPinKey.class);
    assertThat(read.isPinTransport()).isTrue();
    assertThat(read.toPinKey()).isEqualTo(
        new PinKey("PIN_TRANSPORT.v1", "RSA-OAEP-SHA-256", "-----BEGIN PUBLIC KEY-----"));

    String wrong = "{\"code\":\"MW-AUTH-040\",\"message\":\"Wrong PIN\",\"correlationId\":\"c\",\"retryable\":false,"
        + "\"details\":[{\"field\":\"triesLeft\",\"issue\":\"2\"}]}";
    assertThat(OpenApiSchemas.component(AUTH_API, "ErrorEnvelope").validate(JSON.readTree(wrong))).isEmpty();
    assertThat(JSON.readValue(wrong, ErrorEnvelope.class).details())
        .containsExactly(new ErrorDetail("triesLeft", "2"));
  }

  // ---- called: billpay.v1 (BillPayClient) ----

  private HttpServer server;
  private final List<String> requestBodies = new CopyOnWriteArrayList<>();
  private volatile String answer = "[]";
  private BillPayClient client;

  @BeforeEach
  void startAdapter() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", ex -> {
      requestBodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      byte[] b = answer.getBytes(StandardCharsets.UTF_8);
      ex.getResponseHeaders().add("Content-Type", "application/json");
      ex.sendResponseHeaders(200, b.length);
      ex.getResponseBody().write(b);
      ex.close();
    });
    server.start();
    client = new BillPayClient(
        new BillPayProperties(URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
            Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(60)),
        JSON);
  }

  @AfterEach
  void stopAdapter() {
    server.stop(0);
  }

  @Test
  void theBillerCatalogueAnswerDeserialises() {
    answer = "[{\"code\":\"DESCO\",\"name\":\"Dhaka Electric\",\"category\":\"ELECTRICITY\",\"partnerId\":\"billagg\"}]";
    assertThat(OpenApiSchemas.at(BILLPAY_API, "paths", "/billpay/v1/billers", "get", "responses", "200",
        "content", "application/json", "schema").validate(JSON.readTree(answer))).isEmpty();

    assertThat(client.billers()).containsExactly(new Biller("DESCO", "Dhaka Electric", "ELECTRICITY", "billagg"));
  }

  @Test
  void theFetchRequestMatchesTheContractAndTheBillDeserialises() {
    answer = "{\"billerCode\":\"DESCO\",\"billNumber\":\"1234567\",\"amount\":{\"valueMinor\":66700,"
        + "\"currency\":\"BDT\"},\"dueDate\":\"2026-11-14\",\"billPeriod\":\"2026-11\"}";
    assertThat(OpenApiSchemas.component(BILLPAY_API, "Bill").validate(JSON.readTree(answer))).isEmpty();

    Bill bill = client.fetch("DESCO", "1234567");

    assertThat(bill).isEqualTo(new Bill("DESCO", "1234567", new Amount(66_700L, "BDT"), "2026-11-14", "2026-11"));
    assertThat(requestBodies).hasSize(1);
    Schema request = OpenApiSchemas.at(BILLPAY_API, "paths", "/billpay/v1/billers/{code}/bills/fetch", "post",
        "requestBody", "content", "application/json", "schema");
    assertThat(request.validate(JSON.readTree(requestBodies.get(0)))).as(requestBodies.get(0)).isEmpty();
  }

  @Test
  void theOpenApiSchemasReallyValidate() {
    // guard against a $ref that resolves to nothing (which would accept anything)
    assertThat(OpenApiSchemas.component(USSD_API, "UssdScreen").validate(JSON.readTree("{\"flag\":1}")))
        .isNotEmpty();
    assertThat(OpenApiSchemas.at(BILLPAY_API, "paths", "/billpay/v1/billers/{code}/bills/fetch", "post",
        "requestBody", "content", "application/json", "schema").validate(JSON.readTree("{\"billNumber\":\"#\"}")))
        .isNotEmpty();
  }
}
