package mfs.mw.adapter.billpay.partner.route;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import mfs.mw.adapter.billpay.BillpayFixtures;
import mfs.mw.adapter.billpay.BillpayTestTopics;
import mfs.mw.adapter.billpay.TestInfrastructure;
import mfs.mw.adapter.billpay.dto.BillView;
import mfs.mw.adapter.billpay.dto.PartnerReply;
import mfs.mw.adapter.billpay.dto.PartnerRequest;
import mfs.mw.adapter.billpay.exception.MwException;
import mfs.mw.adapter.billpay.messaging.EventMetadata;
import mfs.mw.adapter.billpay.messaging.EventPublisher;
import mfs.mw.adapter.billpay.messaging.KafkaTestConsumer;
import mfs.mw.adapter.billpay.service.BillFetchService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

/**
 * One aggregator, two protocols: the dev seed {@code billagg} (application-dev.yml) sends the SOAP
 * billers (KGDCL, JGTDSL, BTCL, LINK3; the route branches on {@code mwPartnerCode}) to its SOAP front
 * end {@code POST /billagg/v1/soap} with a WS-Security UsernameToken, and every other biller to the
 * REST paths. WireMock stands in for mw-simulators' billagg. A SOAP fault 3001 maps through the same
 * {@code error-map} as REST.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "mw.billpay.request-consumer.enabled=true",
      "mw.outbox.relay.enabled=true",
      "mw.outbox.relay.period=100ms",
      "spring.kafka.consumer.group-id=mw-adapter-billpay-billagg-soap-it"
    })
@Import({TestInfrastructure.class, BillpayTestTopics.class})
@DirtiesContext
class BillaggSoapRouteIT {

  static final JsonMapper JSON = JsonMapper.builder().build();
  static final WireMockServer AGG = new WireMockServer(WireMockConfiguration.options().dynamicPort());
  static final String NS = "urn:billagg:v1";
  static final String SOAP = "/billagg/v1/soap";
  /** {@code FakeVaultFunction}'s value of {@code secret/mw-adapter-billpay/billagg#ws-password}. */
  static final String WS_PASSWORD = "fake-billagg-ws-password-for-tests";

  static {
    AGG.start();
  }

  @DynamicPropertySource
  static void aggregatorUrl(DynamicPropertyRegistry registry) {
    registry.add("mw.partners.instances.billagg.base-url", () -> "http://localhost:" + AGG.port());
  }

  private final BillFetchService fetch;
  private final EventPublisher publisher;
  private final CircuitBreakerRegistry breakers;

  @Autowired
  BillaggSoapRouteIT(BillFetchService fetch, EventPublisher publisher, CircuitBreakerRegistry breakers) {
    this.fetch = fetch;
    this.publisher = publisher;
    this.breakers = breakers;
  }

  @BeforeEach
  void reset() {
    AGG.resetAll();
    breakers.circuitBreaker("billagg").reset();
  }

  static String envelope(String body) {
    return "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body>" + body
        + "</soap:Body></soap:Envelope>";
  }

  static String fault(String code) {
    return envelope("<soap:Fault><faultcode>soap:Client</faultcode><faultstring>bill not found</faultstring>"
        + "<detail><b:error xmlns:b=\"" + NS + "\"><b:code>" + code + "</b:code><b:message>bill not found</b:message>"
        + "</b:error></detail></soap:Fault>");
  }

  @Test
  void aSoapBillerFetchGoesToTheSoapFrontEndWithAUsernameToken() {
    AGG.stubFor(post(urlEqualTo(SOAP)).withRequestBody(containing("<b:FetchBill"))
        .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/xml")
            .withBody(envelope("<b:FetchBillResponse xmlns:b=\"" + NS + "\"><b:billerCode>KGDCL</b:billerCode>"
                + "<b:billNumber>5550001</b:billNumber><b:amountDue>667.00</b:amountDue><b:dueDate>2026-12-01</b:dueDate>"
                + "<b:billPeriod>2026-11</b:billPeriod></b:FetchBillResponse>"))));

    BillView bill = fetch.fetch("KGDCL", "5550001");

    assertThat(bill.amount().valueMinor()).isEqualTo(66_700);
    assertThat(bill.dueDate()).isEqualTo("2026-12-01");
    assertThat(bill.billPeriod()).isEqualTo("2026-11");
    AGG.verify(1, postRequestedFor(urlEqualTo(SOAP))
        .withHeader("SOAPAction", equalTo("\"urn:billagg:v1:FetchBill\""))
        .withHeader("Content-Type", containing("text/xml"))
        .withHeader("mwPartnerCode", absent())
        .withRequestBody(containing("<wsse:Username>bits</wsse:Username>"))
        .withRequestBody(containing(WS_PASSWORD))
        .withRequestBody(containing("<b:billerCode>KGDCL</b:billerCode>"))
        .withRequestBody(containing("<b:billNumber>5550001</b:billNumber>")));
    AGG.verify(0, postRequestedFor(urlEqualTo("/billagg/v1/bills/fetch")));
  }

  @Test
  void aSoapFault3001IsBillNotFound() {
    AGG.stubFor(post(urlEqualTo(SOAP)).willReturn(
        aResponse().withStatus(500).withHeader("Content-Type", "text/xml").withBody(fault("3001"))));

    assertThatThrownBy(() -> fetch.fetch("LINK3", "5550404"))
        .isInstanceOfSatisfying(MwException.class, e -> assertThat(e.code()).isEqualTo("MW-PTR-BILLAGG-BILL_NOT_FOUND"));
  }

  @Test
  void aRestBillerStillGoesToTheRestPath() {
    AGG.stubFor(post(urlEqualTo("/billagg/v1/bills/fetch")).willReturn(aResponse().withStatus(200)
        .withHeader("Content-Type", "application/json")
        .withBody("{\"status\":\"DUE\",\"billerCode\":\"DESCO\",\"billNumber\":\"1234567\",\"amount\":\"667.00\","
            + "\"dueDate\":\"2026-11-14\",\"billPeriod\":\"2026-11\"}")));

    BillView bill = fetch.fetch("DESCO", "1234567");

    assertThat(bill.amount().valueMinor()).isEqualTo(66_700);
    AGG.verify(1, postRequestedFor(urlEqualTo("/billagg/v1/bills/fetch"))
        .withRequestBody(equalToJson("{\"billerCode\":\"DESCO\",\"billNumber\":\"1234567\"}")));
    AGG.verify(0, anyRequestedFor(urlEqualTo(SOAP)));
  }

  @Test
  void aSoapBillerPaymentGoesToTheSoapFrontEnd() throws Exception {
    AGG.stubFor(post(urlEqualTo(SOAP)).withRequestBody(containing("<b:PayBill"))
        .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/xml")
            .withBody(envelope("<b:PayBillResponse xmlns:b=\"" + NS + "\"><b:status>SUCCESS</b:status>"
                + "<b:paymentRef>AGG-SOAP-1</b:paymentRef><b:code>0000</b:code></b:PayBillResponse>"))));

    String txnId = BillpayFixtures.newTxnId();
    PartnerReply reply = pay(txnId, "KGDCL", "5550001", 66_700);

    assertThat(reply.payload().outcome()).isEqualTo("SUCCESS");
    assertThat(reply.payload().partnerRef()).isEqualTo("AGG-SOAP-1");
    String ref = "BITS" + txnId.replace("-", "").toUpperCase();
    AGG.verify(1, postRequestedFor(urlEqualTo(SOAP))
        .withHeader("SOAPAction", equalTo("\"urn:billagg:v1:PayBill\""))
        .withRequestBody(containing("<wsse:Username>bits</wsse:Username>"))
        .withRequestBody(containing(WS_PASSWORD))
        .withRequestBody(containing("<b:billerCode>KGDCL</b:billerCode>"))
        .withRequestBody(containing("<b:billNumber>5550001</b:billNumber>"))
        .withRequestBody(containing("<b:amount>667.00</b:amount>"))
        .withRequestBody(containing("<b:reference>" + ref + "</b:reference>")));
    AGG.verify(0, postRequestedFor(urlEqualTo("/billagg/v1/payments")));
  }

  private PartnerReply pay(String txnId, String billerCode, String billNumber, long valueMinor) throws Exception {
    PartnerRequest base = BillpayFixtures.pay(txnId, billNumber, valueMinor);
    PartnerRequest request = new PartnerRequest(
        base.schemaVersion(), base.family(), UUID.randomUUID().toString(), txnId, base.correlationId(), base.issuedAt(),
        base.deadlineAt(),
        new PartnerRequest.Payload(
            "PAY", "BILL_PAY", new PartnerRequest.PartnerRef("billagg", "BILL", billerCode), "BITS-IGNORED",
            base.payload().amount(), base.payload().debitParty(), base.payload().creditParty(), null, null));
    publisher.publish("mw.partner.request.billpay.v1", txnId,
        new EventMetadata(request.correlationId(), txnId, request.requestId(), 1), request);
    List<ConsumerRecord<String, String>> replies = KafkaTestConsumer.read(
        TestInfrastructure.kafkaBootstrapServers(), "mw.partner.reply.v1", 1, Duration.ofSeconds(30), r -> txnId.equals(r.key()));
    assertThat(replies).hasSize(1);
    return JSON.readValue(replies.getFirst().value(), PartnerReply.class);
  }
}
