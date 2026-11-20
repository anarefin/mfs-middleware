package mfs.mw.adapter.bank.partner.route;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static mfs.mw.adapter.bank.BankFixtures.ACCOUNT;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import mfs.mw.adapter.bank.partner.PartnerCall;
import mfs.mw.adapter.bank.partner.PartnerResponse;
import mfs.mw.adapter.bank.TestInfrastructure;
import mfs.mw.adapter.bank.partner.config.PartnerConfigStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Ticket 117 (FRS EXT-12, UC-14, FL-09): the generated bank gateway profiles. One REST profile
 * ({@code bank-dbbl}, hmac-sha256) and one SOAP profile ({@code bank-sonali}, Basic, the JSON/XML
 * convention) each validate an account (the holder name comes back as its first initial), credit
 * an account for W2B, ask the status, reverse, and map a refusal through the profile's error map.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestInfrastructure.class)
class BankProfileRouteIT {

  static final String TXN = "01933b8e-7c1a-7b2e-9f3d-000000000117";
  static final String REF = "BITS117";
  static final WireMockServer BANK = new WireMockServer(WireMockConfiguration.options().dynamicPort());

  static {
    BANK.start();
  }

  @DynamicPropertySource
  static void gateway(DynamicPropertyRegistry registry) {
    registry.add("BANK_GATEWAY_BASE_URL", () -> "http://localhost:" + BANK.port());
  }

  private final PartnerGateway gateway;
  private final PartnerConfigStore store;

  @Autowired
  BankProfileRouteIT(PartnerGateway gateway, PartnerConfigStore store) {
    this.gateway = gateway;
    this.store = store;
  }

  @BeforeEach
  void reset() {
    BANK.resetAll();
  }

  static PartnerCall avl(String partnerId, String account) {
    return new PartnerCall(partnerId, "INQUIRY", "W2B", TXN, "corr-117", REF,
        Map.of("txnType", "W2B", "account", account,
            "creditParty", Map.of("type", "BANK_ACCOUNT", "id", account)));
  }

  static PartnerCall credit(String partnerId, String account) {
    return new PartnerCall(partnerId, "PAY", "W2B", TXN, "corr-117", REF,
        Map.of("txnType", "W2B", "account", account, "direction", "CREDIT",
            "amount", Map.of("valueMinor", 150000L, "currency", "BDT", "major", "1500.00"),
            "debitParty", Map.of("type", "WALLET", "id", "01711000001"),
            "creditParty", Map.of("type", "BANK_ACCOUNT", "id", account)));
  }

  static PartnerCall status(String partnerId) {
    return new PartnerCall(partnerId, "STATUS_QUERY", "W2B", TXN, "corr-117", REF, Map.of("txnType", "W2B"));
  }

  static PartnerCall reverse(String partnerId) {
    return new PartnerCall(partnerId, "REVERSE", "W2B", TXN, "corr-117", REF,
        Map.of("txnType", "W2B", "direction", "CREDIT",
            "amount", Map.of("valueMinor", 150000L, "currency", "BDT", "major", "1500.00")));
  }

  @Test
  void everyProfileOfTheCsvIsASeededBankPartner() {
    for (String code : List.of("dbbl", "brac", "city", "ebl", "ucb", "mtb", "prime", "pubali", "dhaka", "one",
        "southeast", "standard", "sonali", "janata", "agrani", "rupali", "ibbl", "ab", "premier", "nrbc")) {
      assertThat(store.instance("bank-" + code)).as(code).isPresent();
    }
  }

  @Test
  void everyProfileIsProbedOnTheBanksHealthEndpoint() {
    // ticket 117d: healthCheck true, GET /bank/<code>/v1/health with the profile's HTTP auth (none for ws-security)
    BANK.stubFor(get(urlEqualTo("/bank/dbbl/v1/health"))
        .withHeader("X-Signature", matching(".+"))
        .willReturn(json(200, "{\"status\":\"UP\",\"bank\":\"dbbl\"}")));
    BANK.stubFor(get(urlEqualTo("/bank/janata/v1/health"))
        .willReturn(json(200, "{\"status\":\"UP\",\"bank\":\"janata\"}")));
    BANK.stubFor(get(urlEqualTo("/bank/sonali/v1/health"))
        .willReturn(json(503, "{\"status\":\"UNAVAILABLE\"}")));

    for (String id : List.of("bank-dbbl", "bank-janata")) {
      assertThat(store.instance(id).orElseThrow().config().healthCheck()).as(id).isTrue();
      assertThat(gateway.call(health(id))).as(id).isInstanceOf(PartnerResponse.Success.class);
    }
    assertThat(gateway.call(health("bank-sonali"))).isNotInstanceOf(PartnerResponse.Success.class);
  }

  static PartnerCall health(String partnerId) {
    return new PartnerCall(partnerId, "HEALTH_CHECK", "HEALTH_CHECK", null, UUID.randomUUID().toString(), null, Map.of());
  }

  @Test
  void aRestBankValidatesAnAccountAndAnswersOnlyTheHoldersInitial() {
    BANK.stubFor(post(urlEqualTo("/bank/dbbl/v1/accounts/verify"))
        .withRequestBody(equalToJson("{\"accountNumber\":\"" + ACCOUNT + "\"}"))
        .withHeader("X-Signature", matching(".+"))
        .willReturn(json(200, "{\"status\":\"ACTIVE\",\"accountName\":\"rahim uddin\",\"verifyRef\":\"AVL-1\"}")));
    BANK.stubFor(post(urlEqualTo("/bank/dbbl/v1/accounts/verify"))
        .withRequestBody(equalToJson("{\"accountNumber\":\"9999999901\"}"))
        .willReturn(json(422, "{\"code\":\"4001\",\"message\":\"No such account\"}")));

    PartnerResponse ok = gateway.call(avl("bank-dbbl", ACCOUNT));
    assertThat(ok).isInstanceOfSatisfying(PartnerResponse.Success.class, s -> {
      assertThat(s.partnerRef()).isEqualTo("AVL-1");
      assertThat(s.attributes()).containsEntry("holderName", "R.").containsEntry("holderFullName", "rahim uddin");
    });
    assertThat(gateway.call(avl("bank-dbbl", "9999999901")))
        .isInstanceOfSatisfying(PartnerResponse.Rejected.class,
            r -> assertThat(r.errorSuffix()).isEqualTo("ACCOUNT_NOT_FOUND"));
  }

  @Test
  void aRestBankCreditsForW2bAnswersItsStatusReversesAndRefusesForInsufficientFunds() {
    BANK.stubFor(post(urlEqualTo("/bank/dbbl/v1/transfers"))
        .withRequestBody(equalToJson("{\"transactionId\":\"BITS117\",\"accountNumber\":\"" + ACCOUNT
            + "\",\"amount\":\"1500.00\",\"direction\":\"CREDIT\"}"))
        .willReturn(json(202, "{\"status\":\"PENDING\",\"bankRef\":\"DBBL-1\",\"code\":\"100\"}")));
    BANK.stubFor(get(urlEqualTo("/bank/dbbl/v1/transfers/BITS117"))
        .willReturn(json(200, "{\"status\":\"SUCCESS\",\"bankRef\":\"DBBL-1\",\"code\":\"000\"}")));
    BANK.stubFor(post(urlEqualTo("/bank/dbbl/v1/transfers/BITS117/reverse"))
        .willReturn(json(200, "{\"status\":\"REVERSED\",\"reversalRef\":\"DBBL-R1\",\"code\":\"000\"}")));

    assertThat(gateway.call(credit("bank-dbbl", ACCOUNT))).isInstanceOf(PartnerResponse.Pending.class);
    assertThat(gateway.call(status("bank-dbbl")))
        .isInstanceOfSatisfying(PartnerResponse.Success.class, s -> assertThat(s.partnerRef()).isEqualTo("DBBL-1"));
    assertThat(gateway.call(reverse("bank-dbbl")))
        .isInstanceOfSatisfying(PartnerResponse.Success.class, s -> assertThat(s.partnerRef()).isEqualTo("DBBL-R1"));

    BANK.resetAll();
    BANK.stubFor(post(urlEqualTo("/bank/dbbl/v1/transfers"))
        .willReturn(json(422, "{\"code\":\"4003\",\"message\":\"Insufficient funds\"}")));
    assertThat(gateway.call(credit("bank-dbbl", "1234567899")))
        .isInstanceOfSatisfying(PartnerResponse.Rejected.class,
            r -> assertThat(r.errorSuffix()).isEqualTo("INSUFFICIENT_FUNDS"));
  }

  @Test
  void aSoapBankValidatesCreditsAnswersItsStatusReversesAndMapsAFault() {
    String soap = "/bank/sonali/v1/soap";
    BANK.stubFor(post(urlEqualTo(soap)).withRequestBody(containing("ValidateAccount"))
        .withRequestBody(containing("<accountNumber>" + ACCOUNT + "</accountNumber>"))
        .withHeader("Authorization", matching("Basic .+"))
        .willReturn(xml(200, envelope("<ValidateAccountResponse xmlns=\"urn:bank:sonali:v1\"><status>ACTIVE</status>"
            + "<accountName>Karim Ahmed</accountName><verifyRef>SON-AVL-1</verifyRef></ValidateAccountResponse>"))));
    BANK.stubFor(post(urlEqualTo(soap)).withRequestBody(containing("<Transfer "))
        .withRequestBody(containing("<direction>CREDIT</direction>"))
        .withRequestBody(containing("<amount>1500.00</amount>"))
        .willReturn(xml(200, envelope("<TransferResponse xmlns=\"urn:bank:sonali:v1\"><status>SUCCESS</status>"
            + "<bankRef>SON-1</bankRef><code>000</code></TransferResponse>"))));
    BANK.stubFor(post(urlEqualTo(soap)).withRequestBody(containing("TransferStatus"))
        .willReturn(xml(200, envelope("<TransferStatusResponse xmlns=\"urn:bank:sonali:v1\"><status>SUCCESS</status>"
            + "<bankRef>SON-1</bankRef><code>000</code></TransferStatusResponse>"))));
    BANK.stubFor(post(urlEqualTo(soap)).withRequestBody(containing("ReverseTransfer"))
        .willReturn(xml(200, envelope("<ReverseTransferResponse xmlns=\"urn:bank:sonali:v1\"><status>REVERSED</status>"
            + "<reversalRef>SON-R1</reversalRef></ReverseTransferResponse>"))));

    assertThat(gateway.call(avl("bank-sonali", ACCOUNT)))
        .isInstanceOfSatisfying(PartnerResponse.Success.class, s -> {
          assertThat(s.partnerRef()).isEqualTo("SON-AVL-1");
          assertThat(s.attributes()).containsEntry("holderName", "K.").containsEntry("holderFullName", "Karim Ahmed");
        });
    assertThat(gateway.call(credit("bank-sonali", ACCOUNT)))
        .isInstanceOfSatisfying(PartnerResponse.Success.class, s -> assertThat(s.partnerRef()).isEqualTo("SON-1"));
    assertThat(gateway.call(status("bank-sonali")))
        .isInstanceOfSatisfying(PartnerResponse.Success.class, s -> assertThat(s.partnerRef()).isEqualTo("SON-1"));
    assertThat(gateway.call(reverse("bank-sonali")))
        .isInstanceOfSatisfying(PartnerResponse.Success.class, s -> assertThat(s.partnerRef()).isEqualTo("SON-R1"));
    BANK.verify(postRequestedFor(urlEqualTo(soap)).withRequestBody(containing("<transactionId>BITS117</transactionId>")));

    BANK.resetAll();
    BANK.stubFor(post(urlEqualTo(soap)).willReturn(xml(500, envelope(
        "<soap:Fault><faultcode>soap:Client</faultcode><faultstring>refused</faultstring><detail>"
            + "<b:code xmlns:b=\"urn:bank:sonali:v1\">4002</b:code><b:message xmlns:b=\"urn:bank:sonali:v1\">Closed</b:message>"
            + "</detail></soap:Fault>"))));
    assertThat(gateway.call(avl("bank-sonali", ACCOUNT)))
        .isInstanceOfSatisfying(PartnerResponse.Rejected.class,
            r -> assertThat(r.errorSuffix()).isEqualTo("ACCOUNT_INACTIVE"));
  }

  static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(int status, String body) {
    return aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body);
  }

  static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder xml(int status, String body) {
    return aResponse().withStatus(status).withHeader("Content-Type", "text/xml").withBody(body);
  }

  static String envelope(String body) {
    return "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body>" + body
        + "</soap:Body></soap:Envelope>";
  }
}
