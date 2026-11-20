package mfs.mw.adapter.merchant.route;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import mfs.mw.adapter.merchant.MerchantFixtures;
import mfs.mw.adapter.merchant.MerchantTestTopics;
import mfs.mw.adapter.merchant.TestInfrastructure;
import mfs.mw.adapter.merchant.dto.PartnerReply;
import mfs.mw.adapter.merchant.dto.PartnerRequest;
import mfs.mw.adapter.merchant.messaging.EventMetadata;
import mfs.mw.adapter.merchant.messaging.EventPublisher;
import mfs.mw.adapter.merchant.messaging.KafkaTestConsumer;
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
 * Ticket 115 (FRS UC-11, EXT-11): the dev seed {@code merchant-soap} (application-dev.yml) maps VALIDATE
 * (INQUIRY), NOTIFY (PAY) and REFUND (REVERSE) of a MERCHANT_PAY to the merchant system's SOAP 1.1 front end
 * {@code POST /merchant-soap/v1/soap} (namespace {@code urn:merchant:v1}) with a WS-Security UsernameToken; a
 * {@code soap:Client} fault with a code maps through the record's error map to
 * {@code MW-PTR-MERCHANT_SOAP-<suffix>}, a {@code soap:Server} fault is UNKNOWN. WireMock stands in for the
 * merchant system (mw-simulators' MerchantSoapMock comes with ticket 115b).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "mw.merchant.request-consumer.enabled=true",
      "mw.outbox.relay.enabled=true",
      "mw.outbox.relay.period=100ms",
      "spring.kafka.consumer.group-id=mw-adapter-merchant-soap-it"
    })
@Import({TestInfrastructure.class, MerchantTestTopics.class})
@DirtiesContext
class MerchantSoapRouteIT {

  static final JsonMapper JSON = JsonMapper.builder().build();
  static final WireMockServer MERCHANT = new WireMockServer(WireMockConfiguration.options().dynamicPort());
  static final String NS = "urn:merchant:v1";
  static final String SOAP = "/merchant-soap/v1/soap";
  static final String MERCHANT_ID = "01911000001";
  /** {@code FakeVaultFunction}'s value of {@code secret/mw-adapter-merchant/merchant-soap#ws-password}. */
  static final String WS_PASSWORD = "fake-merchant-soap-ws-password-for-tests";

  static {
    MERCHANT.start();
  }

  @DynamicPropertySource
  static void merchantUrl(DynamicPropertyRegistry registry) {
    registry.add("mw.partners.instances.merchant-soap.base-url", () -> "http://localhost:" + MERCHANT.port());
  }

  private final EventPublisher publisher;
  private final CircuitBreakerRegistry breakers;

  @Autowired
  MerchantSoapRouteIT(EventPublisher publisher, CircuitBreakerRegistry breakers) {
    this.publisher = publisher;
    this.breakers = breakers;
  }

  @BeforeEach
  void reset() {
    MERCHANT.resetAll();
    breakers.circuitBreaker("merchant-soap").reset();
  }

  static String envelope(String body) {
    return "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body>" + body
        + "</soap:Body></soap:Envelope>";
  }

  static String fault(String faultCode, String code, String message) {
    return envelope("<soap:Fault><faultcode>soap:" + faultCode + "</faultcode><faultstring>" + message + "</faultstring>"
        + "<detail><m:error xmlns:m=\"" + NS + "\"><m:code>" + code + "</m:code><m:message>" + message
        + "</m:message></m:error></detail></soap:Fault>");
  }

  private void stub(String element, int status, String body) {
    MERCHANT.stubFor(post(urlEqualTo(SOAP)).withRequestBody(containing("<m:" + element))
        .willReturn(aResponse().withStatus(status).withHeader("Content-Type", "text/xml").withBody(body)));
  }

  @Test
  void aValidateIsAValidateMerchantCallWithTheTillAndAUsernameToken() throws Exception {
    stub("ValidateMerchant", 200, envelope("<m:ValidateMerchantResponse xmlns:m=\"" + NS + "\"><m:status>ACTIVE</m:status>"
        + "<m:merchantRef>M-" + MERCHANT_ID + "</m:merchantRef></m:ValidateMerchantResponse>"));

    PartnerReply reply = send("INQUIRY", MerchantFixtures.newTxnId(), null);

    assertThat(reply.payload().outcome()).isEqualTo("SUCCESS");
    assertThat(reply.payload().partnerRef()).isEqualTo("M-" + MERCHANT_ID);
    MERCHANT.verify(1, postRequestedFor(urlEqualTo(SOAP))
        .withHeader("SOAPAction", equalTo("\"urn:merchant:v1:ValidateMerchant\""))
        .withRequestBody(containing("<wsse:Username>bits</wsse:Username>"))
        .withRequestBody(containing(WS_PASSWORD))
        .withRequestBody(containing("<m:merchantId>" + MERCHANT_ID + "</m:merchantId>"))
        .withRequestBody(containing("<m:tillId>TILL-01</m:tillId>")));
  }

  @Test
  void anInactiveMerchantFaultIsFailedWithTheMappedPartnerCode() throws Exception {
    stub("ValidateMerchant", 500, fault("Client", "5002", "merchant inactive"));

    PartnerReply reply = send("INQUIRY", MerchantFixtures.newTxnId(), null);

    assertThat(reply.payload().outcome()).isEqualTo("FAILED");
    assertThat(reply.payload().errorCode()).isEqualTo("MW-PTR-MERCHANT_SOAP-MERCHANT_INACTIVE");
  }

  @Test
  void aServerFaultIsUnknownNeverFailed() throws Exception {
    stub("NotifyPayment", 500, fault("Server", "9999", "internal"));

    PartnerReply reply = send("PAY", MerchantFixtures.newTxnId(), 66_700L);

    assertThat(reply.payload().outcome()).isEqualTo("UNKNOWN");
  }

  @Test
  void aNotifyCarriesTheAmountAndOurStableReference() throws Exception {
    stub("NotifyPayment", 200, envelope("<m:NotifyPaymentResponse xmlns:m=\"" + NS + "\"><m:status>RECEIVED</m:status>"
        + "<m:notifyRef>NTF-1</m:notifyRef><m:code>0000</m:code></m:NotifyPaymentResponse>"));
    String txnId = MerchantFixtures.newTxnId();

    PartnerReply reply = send("PAY", txnId, 66_700L);

    assertThat(reply.payload().outcome()).isEqualTo("SUCCESS");
    assertThat(reply.payload().partnerRef()).isEqualTo("NTF-1");
    String ref = "BITS" + txnId.replace("-", "").toUpperCase();
    MERCHANT.verify(1, postRequestedFor(urlEqualTo(SOAP))
        .withHeader("SOAPAction", equalTo("\"urn:merchant:v1:NotifyPayment\""))
        .withRequestBody(containing(WS_PASSWORD))
        .withRequestBody(containing("<m:amount>667.00</m:amount>"))
        .withRequestBody(containing("<m:reference>" + ref + "</m:reference>")));
  }

  @Test
  void aRefundCarriesTheOriginalReferenceAndTheRefundId() throws Exception {
    stub("Refund", 200, envelope("<m:RefundResponse xmlns:m=\"" + NS + "\"><m:status>REFUNDED</m:status>"
        + "<m:refundRef>RF-1</m:refundRef><m:code>0000</m:code></m:RefundResponse>"));
    String txnId = MerchantFixtures.newTxnId();

    PartnerReply reply = send("REVERSE", txnId, 66_700L);

    assertThat(reply.payload().outcome()).isEqualTo("SUCCESS");
    assertThat(reply.payload().partnerRef()).isEqualTo("RF-1");
    MERCHANT.verify(1, postRequestedFor(urlEqualTo(SOAP))
        .withHeader("SOAPAction", equalTo("\"urn:merchant:v1:Refund\""))
        .withRequestBody(containing("<m:amount>667.00</m:amount>"))
        .withRequestBody(containing("<m:refundId>reverse:" + txnId + "</m:refundId>")));
  }

  @Test
  void aRefusedRefundIsFailed() throws Exception {
    stub("Refund", 500, fault("Client", "5004", "refund refused"));

    PartnerReply reply = send("REVERSE", MerchantFixtures.newTxnId(), 66_700L);

    assertThat(reply.payload().outcome()).isEqualTo("FAILED");
    assertThat(reply.payload().errorCode()).isEqualTo("MW-PTR-MERCHANT_SOAP-REFUND_REFUSED");
  }

  @Test
  void aStatusQueryAsksForTheNotifyByOurReference() throws Exception {
    stub("QueryNotify", 200, envelope("<m:QueryNotifyResponse xmlns:m=\"" + NS + "\"><m:status>RECEIVED</m:status>"
        + "<m:notifyRef>NTF-7</m:notifyRef></m:QueryNotifyResponse>"));
    String txnId = MerchantFixtures.newTxnId();

    PartnerReply reply = send("STATUS_QUERY", txnId, null);

    assertThat(reply.payload().outcome()).isEqualTo("SUCCESS");
    assertThat(reply.payload().partnerRef()).isEqualTo("NTF-7");
    MERCHANT.verify(1, postRequestedFor(urlEqualTo(SOAP))
        .withHeader("SOAPAction", equalTo("\"urn:merchant:v1:QueryNotify\""))
        .withRequestBody(containing("<m:reference>BITS" + txnId.replace("-", "").toUpperCase() + "</m:reference>")));
  }

  @Test
  void aStatusQueryForANotifyTheMerchantNeverGotIsNotFound() throws Exception {
    stub("QueryNotify", 200, envelope("<m:QueryNotifyResponse xmlns:m=\"" + NS + "\"><m:status>NOT_FOUND</m:status>"
        + "<m:notifyRef></m:notifyRef></m:QueryNotifyResponse>"));

    PartnerReply reply = send("STATUS_QUERY", MerchantFixtures.newTxnId(), null);

    assertThat(reply.payload().outcome()).isEqualTo("NOT_FOUND");
  }

  private PartnerReply send(String operation, String txnId, Long valueMinor) throws Exception {
    PartnerRequest request = new PartnerRequest(
        1, "merchant", UUID.randomUUID().toString(), txnId, "corr-0002", MerchantFixtures.NOW,
        Instant.now().plusSeconds(300),
        new PartnerRequest.Payload(
            operation, "MERCHANT_PAY", new PartnerRequest.PartnerRef("merchant-soap", "MERCHANT", null), "BITS-IGNORED",
            valueMinor == null ? null : new PartnerRequest.Amount(valueMinor, "BDT"),
            new PartnerRequest.Party("WALLET", "01711000001", null, "CUSTOMER"),
            new PartnerRequest.Party("MERCHANT", MERCHANT_ID, null, null),
            null, Map.of("tillId", "TILL-01")));
    publisher.publish("mw.partner.request.merchant.v1", txnId,
        new EventMetadata(request.correlationId(), txnId, request.requestId(), 1), request);
    List<ConsumerRecord<String, String>> replies = KafkaTestConsumer.read(
        TestInfrastructure.kafkaBootstrapServers(), "mw.partner.reply.v1", 1, Duration.ofSeconds(30),
        r -> txnId.equals(r.key()));
    assertThat(replies).hasSize(1);
    return JSON.readValue(replies.getFirst().value(), PartnerReply.class);
  }
}
