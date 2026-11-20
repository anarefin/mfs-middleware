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
import mfs.mw.app.gateway.dto.BoundDevice;
import mfs.mw.app.gateway.dto.LiveSession;
import mfs.mw.app.gateway.dto.EncryptedPin;
import mfs.mw.app.gateway.dto.ErrorDetail;
import mfs.mw.app.gateway.dto.ErrorEnvelope;
import mfs.mw.app.gateway.dto.Favourite;
import mfs.mw.app.gateway.dto.FavouriteList;
import mfs.mw.app.gateway.dto.InboxMessage;
import mfs.mw.app.gateway.dto.ErrorInfo;
import mfs.mw.app.gateway.dto.CoreAccount;
import mfs.mw.app.gateway.dto.LedgerStatement;
import mfs.mw.app.gateway.dto.SavedBiller;
import mfs.mw.app.gateway.dto.WalletLimits;
import mfs.mw.app.gateway.dto.WalletProfile;
import mfs.mw.app.gateway.dto.SavedBillerList;
import mfs.mw.app.gateway.dto.Party;
import mfs.mw.app.gateway.dto.StatusMessage;
import mfs.mw.app.gateway.dto.TransactionRequest;
import mfs.mw.app.gateway.dto.TransactionView;
import mfs.mw.app.gateway.mapper.CollectionMapper;
import mfs.mw.app.gateway.mapper.RequestMapper;
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
  static final String AUTH_API = "api/mw-auth/auth-internal.v1.openapi.yaml";
  static final String QUERY_API = "api/mw-orchestrator/txn-query.v1.openapi.yaml";
  static final String NOTIFY_API = "api/mw-notify/notify-internal.v1.openapi.yaml";
  static final String CORE_API = "api/mw-core-mock/core-wallet.v1.openapi.yaml";
  static final String BANK_API = "api/mw-adapter-bank/bank.v1.openapi.yaml";
  static final String COMPLIANCE_API = "api/mw-compliance/compliance.v1.openapi.yaml";
  static final String TXN_ID = "019a4f6e-1c2d-7a3b-8c4d-5e6f7a8b9c0d";
  static final String CORRELATION_ID = "019a4f6e-0000-7a3b-8c4d-5e6f7a8b9c0d";
  static final Instant NOW = Instant.parse("2026-11-04T06:00:00Z");

  @Test
  void theManifestListsTheseApis() {
    ContractManifest manifest = ContractManifest.load();
    assertThat(manifest.producedApis()).containsExactly(APP_API);
    assertThat(manifest.consumedApis())
        .containsExactly(BILLPAY_API, BANK_API, AUTH_API, AUTH_PUBLIC_API, QUERY_API, NOTIFY_API, CORE_API, COMPLIANCE_API);
  }

  // ---- eKYC onboarding (ticket 124b) ----

  @Test
  void onboardingSendsWhatMwComplianceReadsAndServesWhatItAnswers() {
    mfs.mw.app.gateway.client.ComplianceClient client =
        org.mockito.Mockito.mock(mfs.mw.app.gateway.client.ComplianceClient.class);
    var props = new mfs.mw.app.gateway.config.ComplianceClientProperties(
        java.net.URI.create("https://mw-compliance:8080"), java.time.Duration.ofSeconds(1),
        java.time.Duration.ofSeconds(20), 2 * 1024 * 1024, java.util.Set.of("image/jpeg", "image/png"));
    var service = new mfs.mw.app.gateway.service.OnboardingService(client, props);
    var dso = new mfs.mw.app.gateway.dto.AppUser("01811000017", "DSO_APP", "DSO", "device-1");
    var request = new mfs.mw.app.gateway.dto.OnboardingApplicationRequest("01711000001", "1990123456789",
        java.time.LocalDate.parse("1990-05-17"), "Rahim Uddin", "রহিম উদ্দিন",
        new mfs.mw.app.gateway.dto.OnboardingApplicationRequest.Consent("EKYC_ONBOARDING", NOW));
    assertValid(OpenApiSchemas.component(APP_API, "OnboardingApplicationRequest"), request);

    service.create(dso, request);
    org.mockito.ArgumentCaptor<Object> sent = org.mockito.ArgumentCaptor.forClass(Object.class);
    org.mockito.Mockito.verify(client).post(org.mockito.ArgumentMatchers.eq("/internal/v1/kyc/applications"),
        sent.capture(), org.mockito.ArgumentMatchers.any());
    assertValid(OpenApiSchemas.component(COMPLIANCE_API, "CreateApplication"), sent.getValue());

    var document = new mfs.mw.app.gateway.dto.OnboardingDocumentRequest("SELFIE", "image/png", "iVBORw0KGgo=");
    assertValid(OpenApiSchemas.component(APP_API, "OnboardingDocumentRequest"), document);
    assertValid(OpenApiSchemas.component(COMPLIANCE_API, "DocumentUpload"), document);

    String answer = "{\"applicationId\":\"11111111-1111-4111-8111-111111111111\",\"walletId\":\"01711000001\","
        + "\"channel\":\"DSO\",\"agentId\":\"01811000017\",\"consentId\":\"22222222-2222-4222-8222-222222222222\","
        + "\"status\":\"APPROVED\",\"nidMasked\":\"****789\",\"matchScore\":1.0,\"kycLevel\":\"FULL\","
        + "\"retryable\":false,\"documents\":[\"SELFIE\"],\"createdAt\":\"2026-11-04T06:00:00Z\","
        + "\"updatedAt\":\"2026-11-04T06:00:00Z\"}";
    assertThat(OpenApiSchemas.component(COMPLIANCE_API, "Application").validate(JSON.readTree(answer))).isEmpty();
    assertThat(OpenApiSchemas.component(APP_API, "OnboardingApplication").validate(JSON.readTree(answer))).isEmpty();
  }

  // ---- linked bank accounts (ticket 117b) ----

  @Test
  void linkedBankAccountsMatchTheAppApiAndTheBankAnswerReads() {
    var row = new mfs.mw.app.gateway.dto.LinkedBankAccount(
        "11111111-1111-1111-1111-111111111111", "dbbl", "7890", "R.", java.time.Instant.parse("2026-11-20T08:00:00Z"));
    assertValid(OpenApiSchemas.component(APP_API, "LinkedBankAccount"), row);
    assertValid(OpenApiSchemas.component(APP_API, "LinkedBankAccountList"),
        new mfs.mw.app.gateway.dto.LinkedBankAccount.Page(List.of(row)));
    assertValid(OpenApiSchemas.component(BANK_API, "ValidateAccountRequest"),
        java.util.Map.of("bankCode", "dbbl", "accountNumber", "1234567890"));
    // M9 review owner binding: the KYC name as matchName, the answer's nameMatch
    assertValid(OpenApiSchemas.component(BANK_API, "ValidateAccountRequest"),
        java.util.Map.of("bankCode", "dbbl", "accountNumber", "1234567890", "matchName", "Rahim Uddin"));
    String answer = "{\"bankCode\":\"dbbl\",\"accountLast4\":\"7890\",\"holderName\":\"R.\",\"nameMatch\":true}";
    assertThat(OpenApiSchemas.component(BANK_API, "AccountValidation").validate(JSON.readTree(answer))).isEmpty();
    assertThat(JSON.readValue(answer, mfs.mw.app.gateway.dto.BankAccountValidation.class))
        .isEqualTo(new mfs.mw.app.gateway.dto.BankAccountValidation("dbbl", "7890", "R.", true));
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
        "en", Map.of(), NOW, NOW.plusSeconds(2), null);
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

  /** Ticket 105: the preview answer (from a contract-shaped orchestrator quote) and the quote request. */
  @Test
  void thePreviewAndTheQuoteMatchTheirContracts() {
    String quote = "{\"txnType\":\"P2P\",\"amount\":{\"valueMinor\":1000,\"currency\":\"BDT\"},"
        + "\"fee\":{\"valueMinor\":500,\"currency\":\"BDT\"},\"vat\":{\"valueMinor\":65,\"currency\":\"BDT\"},"
        + "\"commission\":{\"valueMinor\":0,\"currency\":\"BDT\"},\"total\":{\"valueMinor\":1500,\"currency\":\"BDT\"},"
        + "\"limits\":{\"allowed\":true,\"remaining\":{\"perTxn\":{\"valueMinor\":2500000,\"currency\":\"BDT\"},\"dailyCount\":4}},"
        + "\"counterparty\":{\"type\":\"WALLET\",\"id\":\"0171****002\",\"name\":\"R.\",\"walletType\":\"CUSTOMER\"}}";
    assertThat(OpenApiSchemas.component(QUERY_API, "Quote").validate(JSON.readTree(quote))).isEmpty();
    mfs.mw.app.gateway.dto.Quote q = JSON.readValue(quote, mfs.mw.app.gateway.dto.Quote.class);

    assertValid(OpenApiSchemas.component(APP_API, "Preview"), new mfs.mw.app.gateway.dto.PreviewView(
        "0190a7c4-0000-4000-8000-000000000001.c2ln", NOW, "P2P", q.amount(), q.fee(), q.vat(), q.commission(), q.total(),
        q.counterparty(), new mfs.mw.app.gateway.dto.PreviewView.Limits(q.limits().remaining())));
    assertValid(OpenApiSchemas.component(QUERY_API, "QuoteRequest"), new mfs.mw.app.gateway.dto.QuoteRequest(
        "01711000001", "CUSTOMER", "TOPUP", "CUSTOMER_APP", new Amount(5_000L, "BDT"),
        new mfs.mw.app.gateway.dto.QuoteRequest.Counterparty("MSISDN", "01811000002")));
  }

  @Test
  void theBalanceMatchesTheContract() {
    assertValid(OpenApiSchemas.component(APP_API, "Balance"), new mfs.mw.app.gateway.dto.BalanceView(
        TXN_ID, new Amount(1_200_000L, "BDT"), NOW));
    assertValid(OpenApiSchemas.component(APP_API, "Balance"), new mfs.mw.app.gateway.dto.BalanceView(
        TXN_ID, new Amount(0L, "BDT"), NOW));
  }

  @Test
  void theBalanceAuthorisationMatchesMwAuthsContract() {
    RequestMapper mapper = new RequestMapper();
    AppUser user = new AppUser("01811000001", "AGENT_APP", "AGENT", "device-0001");
    var req = mapper.toBalanceRequest(user, "key-b", null, TXN_ID, CORRELATION_ID, NOW);
    assertValid(OpenApiSchemas.component(AUTH_API, "TxnAuthorisationRequest"),
        mapper.toAuthorisation(user, user.deviceId(), req, new EncryptedPin("PIN_TRANSPORT.v1", "b3BhcXVl")));
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
  void savedBillersFavouritesAndTheInboxMatchTheContract() {
    SavedBiller biller = new SavedBiller("11111111-1111-1111-1111-111111111111", "DESCO", "9876543210", "home", NOW);
    assertValid(OpenApiSchemas.component(APP_API, "SavedBillerList"), new SavedBillerList(List.of(biller)));
    assertValid(OpenApiSchemas.component(APP_API, "SavedBiller"),
        new SavedBiller(biller.id(), "DESCO", "9876543210", null, NOW));
    Favourite favourite = new Favourite("22222222-2222-2222-2222-222222222222", "01711000099", null, NOW);
    assertValid(OpenApiSchemas.component(APP_API, "FavouriteList"), new FavouriteList(List.of(favourite)));
    InboxMessage item = new InboxMessage("m-1", "SMS", null, "0171****001", "TXN", "You sent Tk 10.00", false, NOW);
    assertValid(OpenApiSchemas.component(APP_API, "InboxPage"), new InboxMessage.Page(List.of(item), 20, 0));
    String page = "{\"items\":[{\"id\":\"m-1\",\"channel\":\"SMS\",\"msisdn\":\"0171****077\",\"priority\":\"TXN\","
        + "\"body\":\"You sent Tk 10.00\",\"read\":false,\"createdAt\":\"2026-11-20T08:00:00Z\"}],\"limit\":2,"
        + "\"offset\":0}";
    assertThat(OpenApiSchemas.component(NOTIFY_API, "InboxPage").validate(JSON.readTree(page))).isEmpty();
    assertThat(JSON.readValue(page, InboxMessage.Page.class).items().getFirst().msisdn()).isEqualTo("0171****077");
  }

  @Test
  void profileLimitsAndTheStatementMatchTheContract() {
    assertValid(OpenApiSchemas.component(APP_API, "WalletProfile"),
        new WalletProfile("Rahim Uddin", "CUSTOMER", "ACTIVE", "FULL", "bn"));
    assertValid(OpenApiSchemas.component(APP_API, "WalletProfile"),
        new WalletProfile(null, "CUSTOMER", "ACTIVE", "BASIC", "bn"));
    assertValid(OpenApiSchemas.component(APP_API, "WalletLimits"), new WalletLimits(true, null,
        new WalletLimits.Remaining(new Amount(500_000L, "BDT"), new Amount(0L, "BDT"), null, 4L, null)));
    assertValid(OpenApiSchemas.component(APP_API, "LedgerStatement"), new LedgerStatement(NOW, NOW.plusSeconds(60), 0, false,
        List.of(new LedgerStatement.LedgerEntry(TXN_ID, "DEBIT", "TRANSFER", new Amount(100_000L, "BDT"),
            new Amount(900_000L, "BDT"), NOW))));
    String account = "{\"walletId\":\"01711000001\",\"msisdn\":\"01711000001\",\"type\":\"CUSTOMER\",\"status\":\"ACTIVE\","
        + "\"kycLevel\":\"FULL\",\"holderName\":\"Rahim Uddin\"}";
    assertThat(OpenApiSchemas.component(CORE_API, "Account").validate(JSON.readTree(account))).isEmpty();
    assertThat(JSON.readValue(account, CoreAccount.class).holderName()).isEqualTo("Rahim Uddin");
    String limits = "{\"walletId\":\"01711000001\",\"txnType\":\"P2P\",\"amount\":{\"valueMinor\":100,\"currency\":\"BDT\"}}";
    assertThat(OpenApiSchemas.component(CORE_API, "LimitsRequest").validate(JSON.readTree(limits))).isEmpty();
  }

  // ---- served: the wallet history; called: mw-orchestrator's query API (ADR D25) ----

  static final String QUERY_PAGE = "{\"items\":["
      + "{\"txnId\":\"" + TXN_ID + "\",\"direction\":\"OUT\",\"txnType\":\"P2P\",\"channel\":\"CUSTOMER_APP\","
      + "\"status\":\"FAILED\",\"createdAt\":\"2026-11-16T06:00:00Z\",\"updatedAt\":\"2026-11-16T06:00:02Z\","
      + "\"correlationId\":\"c-1\",\"amount\":{\"valueMinor\":5000,\"currency\":\"BDT\"},"
      + "\"fee\":{\"valueMinor\":0,\"currency\":\"BDT\"},"
      + "\"initiator\":{\"type\":\"MSISDN\",\"id\":\"0171****001\"},"
      + "\"debitParty\":{\"type\":\"WALLET\",\"id\":\"0171****001\",\"walletType\":\"CUSTOMER\"},"
      + "\"creditParty\":{\"type\":\"WALLET\",\"id\":\"0171****002\",\"name\":\"R\",\"walletType\":\"CUSTOMER\"},"
      + "\"error\":{\"code\":\"MW-CORE-INSUFFICIENT_BALANCE\",\"message\":\"Insufficient balance\",\"retryable\":false}},"
      + "{\"txnId\":\"019a4f6e-1c2d-7a3b-8c4d-5e6f7a8b9c0e\",\"direction\":\"IN\",\"txnType\":\"BULK_DISBURSE\","
      + "\"channel\":\"ORG_PORTAL\",\"status\":\"COMPLETED\",\"createdAt\":\"2026-11-16T05:00:00Z\","
      + "\"updatedAt\":\"2026-11-16T05:00:01Z\",\"amount\":{\"valueMinor\":1000,\"currency\":\"BDT\"},"
      + "\"debitParty\":{\"type\":\"ORG\",\"id\":\"*******0001\"},"
      + "\"creditParty\":{\"type\":\"WALLET\",\"id\":\"0171****001\",\"walletType\":\"CUSTOMER\"},"
      + "\"coreRef\":\"CORE-9\",\"attributes\":{\"parentTxnId\":\"p-1\"}}],\"limit\":21,\"offset\":0}";

  @Test
  void aContractShapedWalletPageDeserialisesAndItsHistoryMatchesTheAppContract() {
    assertThat(OpenApiSchemas.component(QUERY_API, "TxnPage").validate(JSON.readTree(QUERY_PAGE))).isEmpty();
    mfs.mw.app.gateway.dto.QueryTxnPage page = JSON.readValue(QUERY_PAGE, mfs.mw.app.gateway.dto.QueryTxnPage.class);
    assertThat(page.items()).extracting(mfs.mw.app.gateway.dto.QueryTxn::direction).containsExactly("OUT", "IN");

    mfs.mw.app.gateway.mapper.HistoryMapper mapper = new mfs.mw.app.gateway.mapper.HistoryMapper();
    Schema schema = OpenApiSchemas.component(APP_API, "TransactionHistory");
    assertValid(schema, new mfs.mw.app.gateway.dto.TransactionHistory(
        page.items().stream().map(mapper::toItem).toList(), 20, 0, false));
    assertValid(schema, new mfs.mw.app.gateway.dto.TransactionHistory(List.of(), 20, 40, false));
    // guard: a direction outside the enum is refused
    assertThat(schema.validate(JSON.readTree("{\"items\":[{\"txnId\":\"x\",\"txnType\":\"P2P\",\"direction\":\"BOTH\","
        + "\"status\":\"COMPLETED\",\"amount\":{\"valueMinor\":1,\"currency\":\"BDT\"},"
        + "\"counterparty\":{\"type\":\"WALLET\",\"id\":\"0171****002\"},\"requestedAt\":\"2026-11-16T05:00:00Z\","
        + "\"updatedAt\":\"2026-11-16T05:00:00Z\"}],\"limit\":20,\"offset\":0,\"hasMore\":false}"))).isNotEmpty();
  }

  // ---- called: mw-auth's internal API (AuthClient, ticket 68) ----

  @Test
  void aContractShapedBoundDeviceDeserialises() {
    String answer = "{\"deviceId\":\"device-0001\",\"walletId\":\"01711000001\","
        + "\"publicKey\":\"MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE\",\"boundAt\":\"2026-11-14T06:00:00Z\"}";
    assertThat(OpenApiSchemas.component(AUTH_API, "BoundDevice").validate(JSON.readTree(answer))).isEmpty();

    assertThat(JSON.readValue(answer, BoundDevice.class)).isEqualTo(new BoundDevice(
        "device-0001", "01711000001", "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE", Instant.parse("2026-11-14T06:00:00Z")));
  }

  @Test
  void theAuthClientPathsAreInTheContract() {
    assertThat(OpenApiSchemas.at(AUTH_API, "paths", "/internal/v1/devices/{deviceId}", "get", "responses", "200",
        "content", "application/json", "schema").validate(JSON.readTree("{}"))).isNotEmpty();
    assertThat(OpenApiSchemas.at(AUTH_API, "paths", "/internal/v1/sessions/{walletId}/{deviceId}", "get", "responses",
        "200", "content", "application/json", "schema").validate(JSON.readTree(
            "{\"walletId\":\"01711000001\",\"deviceId\":\"device-0001\",\"idleTimeoutSeconds\":300,\"deviceKeyFingerprint\":\"abababababababababababababababababababababababababababababababab\",\"boundAt\":\"2026-11-14T06:00:00Z\",\"bindingEpoch\":42}")))
        .isEmpty();
  }

  static final String AUTH_PUBLIC_API = "api/mw-auth/auth.v1.openapi.yaml";

  @Test
  void theAuthorisationRequestMatchesMwAuthsContractAndItsAnswersDeserialise() {
    RequestMapper mapper = new RequestMapper();
    AppUser user = new AppUser("01711000001", "CUSTOMER_APP", "CUSTOMER", "device-0001");
    TransactionRequest p2p = new TransactionRequest(
        "P2P", new TransactionRequest.CounterParty(null, "01711000002"), new Amount(12_345L, "BDT"),
        new EncryptedPin("PIN_TRANSPORT.v1", "b3BhcXVl"), "en", null);
    var req = mapper.toRequest(user, "key-1", p2p, TXN_ID, CORRELATION_ID, NOW);
    assertValid(OpenApiSchemas.component(AUTH_API, "TxnAuthorisationRequest"),
        mapper.toAuthorisation(user, user.deviceId(), req, p2p.encryptedPin()));

    String answer = "{\"assertion\":\"aaa.bbb.ccc\",\"expiresAt\":\"2026-11-15T06:01:00Z\",\"newDevice\":false}";
    assertThat(OpenApiSchemas.component(AUTH_API, "TxnAuthorisation").validate(JSON.readTree(answer))).isEmpty();
    assertThat(JSON.readValue(answer, mfs.mw.app.gateway.dto.TxnAuthorisation.class).assertion()).isEqualTo("aaa.bbb.ccc");

    String key = "{\"keyId\":\"PIN_TRANSPORT.v1\",\"algorithm\":\"RSA-OAEP-SHA-256\",\"publicKeyPem\":\"-----BEGIN PUBLIC KEY-----\"}";
    assertThat(OpenApiSchemas.component(AUTH_PUBLIC_API, "PinKey").validate(JSON.readTree(key))).isEmpty();
    // what the gateway reads: mw-auth's internal key with its purpose (security review of ticket 72)
    String internal = "{\"keyId\":\"PIN_TRANSPORT.v1\",\"algorithm\":\"RSA-OAEP-SHA-256\","
        + "\"publicKeyPem\":\"-----BEGIN PUBLIC KEY-----\",\"purpose\":\"PIN_TRANSPORT\"}";
    assertThat(OpenApiSchemas.component(AUTH_API, "ChannelPinKey").validate(JSON.readTree(internal))).isEmpty();
    assertThat(JSON.readValue(internal, mfs.mw.app.gateway.dto.ChannelPinKey.class).isPinTransport()).isTrue();
    // served to the apps unchanged (GET /app/v1/pin-key)
    assertValid(OpenApiSchemas.component(APP_API, "PinKey"), JSON.readValue(key, mfs.mw.app.gateway.dto.PinKey.class));
  }

  @Test
  void aContractShapedLiveSessionDeserialises() {
    String answer = "{\"walletId\":\"01711000001\",\"deviceId\":\"device-0001\",\"idleTimeoutSeconds\":300,\"deviceKeyFingerprint\":\"abababababababababababababababababababababababababababababababab\",\"boundAt\":\"2026-11-14T06:00:00Z\",\"bindingEpoch\":42}";
    assertThat(OpenApiSchemas.component(AUTH_API, "LiveSession").validate(JSON.readTree(answer))).isEmpty();
    assertThat(JSON.readValue(answer, LiveSession.class)).isEqualTo(new LiveSession(
        "01711000001", "device-0001", 300, "abababababababababababababababababababababababababababababababab", Instant.parse("2026-11-14T06:00:00Z"), 42L));
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
