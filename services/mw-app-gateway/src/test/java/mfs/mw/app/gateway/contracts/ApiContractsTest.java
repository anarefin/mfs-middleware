package mfs.mw.app.gateway.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.Schema;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import mfs.mw.app.gateway.client.BillPayClient;
import mfs.mw.app.gateway.config.BillPayProperties;
import mfs.mw.app.gateway.dto.Amount;
import mfs.mw.app.gateway.dto.AppUser;
import mfs.mw.app.gateway.dto.Bill;
import mfs.mw.app.gateway.dto.Biller;
import mfs.mw.app.gateway.dto.BillerList;
import mfs.mw.app.gateway.dto.CollectionList;
import mfs.mw.app.gateway.dto.DeviceOtpSent;
import mfs.mw.app.gateway.dto.DeviceSession;
import mfs.mw.app.gateway.dto.EncryptedPin;
import mfs.mw.app.gateway.dto.ErrorDetail;
import mfs.mw.app.gateway.dto.ErrorEnvelope;
import mfs.mw.app.gateway.dto.ErrorInfo;
import mfs.mw.app.gateway.dto.Party;
import mfs.mw.app.gateway.dto.StatusMessage;
import mfs.mw.app.gateway.dto.TransactionRequest;
import mfs.mw.app.gateway.dto.TransactionView;
import mfs.mw.app.gateway.mapper.CollectionMapper;
import mfs.mw.app.gateway.mapper.RequestMapper;
import mfs.mw.app.gateway.util.MaskingPolicy;
import mfs.mw.app.gateway.util.SensitiveType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * API contracts of {@code contracts.yml}: the response bodies of the app API this module serves
 * ({@code api/mw-app-gateway/app.v1.openapi.yaml}), built from fixtures through the real mappers
 * and serialised with the service's JsonMapper (as Spring MVC writes them), validate against the
 * OpenAPI component schemas; the requests {@code BillPayClient} sends to mw-adapter-billpay
 * ({@code api/mw-adapter-billpay/billpay.v1.openapi.yaml}) validate against that API, and
 * contract-shaped answers deserialise into its DTOs. No Spring context, no Docker: the adapter
 * is a JDK {@link HttpServer} on a loopback port.
 */
class ApiContractsTest {

  static final JsonMapper JSON = ServiceJson.MAPPER;
  static final String APP_API = "api/mw-app-gateway/app.v1.openapi.yaml";
  static final String BILLPAY_API = "api/mw-adapter-billpay/billpay.v1.openapi.yaml";
  static final String TXN_ID = "019a4f6e-1c2d-7a3b-8c4d-5e6f7a8b9c0d";
  static final String CORRELATION_ID = "019a4f6e-0000-7a3b-8c4d-5e6f7a8b9c0d";
  static final Instant NOW = Instant.parse("2026-11-04T06:00:00Z");

  @Test
  void theManifestListsTheseApis() {
    ContractManifest manifest = ContractManifest.load();
    assertThat(manifest.producedApis()).containsExactly(APP_API);
    assertThat(manifest.consumedApis()).containsExactly(BILLPAY_API);
  }

  // ---- served: app.v1 ----

  static JsonNode served(Object body) {
    return JSON.readTree(JSON.writeValueAsString(body));
  }

  static void assertValid(Schema schema, Object body) {
    JsonNode tree = served(body);
    assertThat(schema.validate(tree)).as("%s", tree).isEmpty();
  }

  static StatusMessage status(String txnType, String state, Party credit, ErrorInfo error) {
    return new StatusMessage(
        1, TXN_ID, "key-1", CORRELATION_ID, "CUSTOMER_APP", txnType, state, "CORE_PENDING",
        Party.wallet("01711000001", "CUSTOMER"), Party.wallet("01711000001", "CUSTOMER"), credit,
        new Amount(12_345L, "BDT"), new Amount(500L, "BDT"), error == null ? "CORE-REF-1" : null, error,
        "en", Map.of(), NOW, NOW.plusSeconds(2));
  }

  @Test
  void transactionResponsesMatchTheContract() {
    Schema schema = OpenApiSchemas.component(APP_API, "Transaction");
    TransactionRequest p2p =
        new TransactionRequest("P2P", new TransactionRequest.CounterParty(null, "01711000002"),
            new Amount(12_345L, "BDT"), new EncryptedPin("core-pin-1", "b3BhcXVl"), "en", null);
    RequestMapper mapper = new RequestMapper();
    // seeded at submit (GET before any status), 202 PROCESSING, and the views of status records
    assertValid(schema, mapper.accepted(mapper.toRequest(
        new AppUser("01711000001", "CUSTOMER_APP", "CUSTOMER"), "key-1", p2p, TXN_ID, CORRELATION_ID, NOW)));
    assertValid(schema, TransactionView.processing(TXN_ID));
    assertValid(schema, TransactionView.of(status("P2P", "COMPLETED", Party.wallet("01711000002", "CUSTOMER"), null)));
    assertValid(schema, TransactionView.of(status("BILL_PAY", "REVERSED",
        new Party("BILLER_ACCOUNT", "1234567", null, null), null)));
    assertValid(schema, TransactionView.of(status("P2P", "FAILED", Party.wallet("01711000002", "CUSTOMER"),
        new ErrorInfo("MW-CORE-INSUFFICIENT_FUNDS", "Insufficient funds", "অপর্যাপ্ত ব্যালেন্স", false))));
  }

  @Test
  void collectionsMatchTheContract() {
    CollectionMapper mapper = new CollectionMapper();
    CollectionList list = new CollectionList(List.of(
        mapper.toCollection(TransactionView.of(status("MERCHANT_PAY", "COMPLETED",
            Party.wallet("01911000001", "MERCHANT"), null))),
        mapper.toCollection(TransactionView.of(status("MERCHANT_PAY", "REVERSED",
            Party.wallet("01911000001", "MERCHANT"), null)))));
    assertValid(OpenApiSchemas.component(APP_API, "CollectionList"), list);
    assertValid(OpenApiSchemas.component(APP_API, "CollectionList"), new CollectionList(List.of()));
  }

  @Test
  void billersAndBillMatchTheContract() {
    // BillerService.billers() hides the aggregator
    assertValid(OpenApiSchemas.component(APP_API, "BillerList"), new BillerList(List.of(
        new Biller("DESCO", "Dhaka Electric", "ELECTRICITY", "billagg").withoutPartner(),
        new Biller("WASA", "Dhaka WASA", null, "billagg").withoutPartner())));
    assertValid(OpenApiSchemas.component(APP_API, "Bill"),
        new Bill("DESCO", "1234567", new Amount(66_700L, "BDT"), "2026-11-14", "2026-11"));
    assertValid(OpenApiSchemas.component(APP_API, "Bill"),
        new Bill("DESCO", "1234567", new Amount(66_700L, "BDT"), null, null));
  }

  @Test
  void deviceResponsesMatchTheContract() {
    assertValid(OpenApiSchemas.component(APP_API, "DeviceOtpSent"),
        new DeviceOtpSent(MaskingPolicy.mask(SensitiveType.MSISDN, "01711000001"), NOW.plusSeconds(120), 4));
    assertValid(OpenApiSchemas.component(APP_API, "DeviceSession"), new DeviceSession("device-1", 300));
  }

  @Test
  void errorEnvelopesMatchTheContract() {
    Schema schema = OpenApiSchemas.component(APP_API, "ErrorEnvelope");
    assertValid(schema, new ErrorEnvelope("MW-VAL-001", "Invalid request", null, null, CORRELATION_ID, false,
        List.of(new ErrorDetail("amount.valueMinor", "must be greater than 0"))));
    assertValid(schema, new ErrorEnvelope("MW-PTR-GP-INVALID_NUMBER", "Refused by the operator", "প্রত্যাখ্যাত",
        TXN_ID, CORRELATION_ID, false, null));
    assertValid(schema, new ErrorEnvelope("MW-SYS-010", "Try again", null, null, CORRELATION_ID, true, List.of()));
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
    assertThat(OpenApiSchemas.component(APP_API, "Transaction").validate(JSON.readTree("{\"status\":1}")))
        .isNotEmpty();
    assertThat(OpenApiSchemas.at(BILLPAY_API, "paths", "/billpay/v1/billers/{code}/bills/fetch", "post",
        "requestBody", "content", "application/json", "schema").validate(JSON.readTree("{\"billNumber\":\"#\"}")))
        .isNotEmpty();
  }
}
