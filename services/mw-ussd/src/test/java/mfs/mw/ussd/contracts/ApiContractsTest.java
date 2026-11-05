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
import mfs.mw.ussd.dto.ErrorEnvelope;
import mfs.mw.ussd.dto.HttpUssdResponse;
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
  static final String CORRELATION_ID = "019a4f6e-0000-7a3b-8c4d-5e6f7a8b9c0d";

  @Test
  void theManifestListsTheseApis() {
    ContractManifest manifest = ContractManifest.load();
    assertThat(manifest.producedApis()).containsExactly(USSD_API);
    assertThat(manifest.consumedApis()).containsExactly(BILLPAY_API);
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
