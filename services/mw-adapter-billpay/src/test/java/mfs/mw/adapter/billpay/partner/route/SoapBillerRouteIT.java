package mfs.mw.adapter.billpay.partner.route;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
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
import mfs.mw.adapter.billpay.partner.config.PartnerConfigKafkaListener;
import mfs.mw.adapter.billpay.partner.config.PartnerConfigStore;
import mfs.mw.adapter.billpay.service.BillFetchService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * A SOAP biller with no restart and no Java change (ticket 85 acceptance): a {@code
 * mw.config.changed.v1} record for {@code soapbill} (a SOAP 1.1 service, XSLT request and response
 * stylesheets in the record, WS-Security UsernameToken with its password from the vault function)
 * arrives on Kafka; a bill fetch and a payment go through {@code mw-soap-call} against the stub
 * biller (WireMock standing in for mw-simulators' SOAP stub, which this module cannot depend on),
 * and a SOAP fault maps through the record's {@code errorMap} to {@code MW-PTR-SOAPBILL-*}.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "mw.billpay.request-consumer.enabled=true",
      "mw.partners.config-consumer.enabled=true",
      "mw.outbox.relay.enabled=true",
      "mw.outbox.relay.period=100ms",
      "spring.kafka.consumer.group-id=mw-adapter-billpay-soapbiller-it",
      "mw.billers.SOAPWATER.name=Soap Water",
      "mw.billers.SOAPWATER.category=WATER",
      "mw.billers.SOAPWATER.partner-id=soapbill"
    })
@Import({TestInfrastructure.class, BillpayTestTopics.class})
@DirtiesContext
class SoapBillerRouteIT {

  static final JsonMapper JSON = JsonMapper.builder().build();
  static final WireMockServer BILLER = new WireMockServer(WireMockConfiguration.options().dynamicPort());
  static final String NS = "urn:soapbill:v1";
  /** {@code FakeVaultFunction}'s value of {@code secret/mw-adapter-billpay/soapbill#ws-password}. */
  static final String WS_PASSWORD = "fake-soapbill-ws-password-for-tests";

  static {
    BILLER.start();
  }

  private final PartnerConfigStore store;
  private final BillFetchService fetch;
  private final EventPublisher publisher;

  @Autowired
  SoapBillerRouteIT(PartnerConfigStore store, BillFetchService fetch, EventPublisher publisher) {
    this.store = store;
    this.fetch = fetch;
    this.publisher = publisher;
  }

  static final String REQUEST_XSL = """
      <xsl:stylesheet version="3.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
          xmlns:fn="http://www.w3.org/2005/xpath-functions" xmlns:s="urn:soapbill:v1" exclude-result-prefixes="fn">
        <xsl:param name="op"/>
        <xsl:template match="/fn:map[fn:string[@key='op'] = 'PAY']">
          <s:PayBill>
            <s:billNumber><xsl:value-of select="fn:string[@key='bill']"/></s:billNumber>
            <s:amount><xsl:value-of select="fn:string[@key='amount']"/></s:amount>
            <s:reference><xsl:value-of select="fn:string[@key='ref']"/></s:reference>
          </s:PayBill>
        </xsl:template>
        <xsl:template match="/fn:map" priority="-1">
          <s:FetchBill><s:billNumber><xsl:value-of select="fn:string[@key='bill']"/></s:billNumber></s:FetchBill>
        </xsl:template>
      </xsl:stylesheet>
      """;

  static final String RESPONSE_XSL = """
      <xsl:stylesheet version="3.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
          xmlns:s="urn:soapbill:v1" xmlns:f="urn:bits:mw:soap-fault">
        <xsl:output method="json"/>
        <xsl:template match="/s:PayBillResponse">
          <xsl:sequence select="map{'receipt': string(s:receipt)}"/>
        </xsl:template>
        <xsl:template match="/s:FetchBillResponse">
          <xsl:sequence select="map{'bill': string(s:billNumber), 'amount': string(s:amountDue), 'due': string(s:dueDate)}"/>
        </xsl:template>
        <xsl:template match="/f:fault">
          <xsl:sequence select="map{'fault': string(@code), 'code': string(f:detail/s:error/s:code)}"/>
        </xsl:template>
      </xsl:stylesheet>
      """;

  /** The SOAP biller's record, as mw-admin-api publishes it: route, mappings, stylesheets, hosts; no Java. */
  static Map<String, Object> soapbill(int port) {
    String call = "kamelet:mw-soap-call?address=http://localhost:%d/soapbill/v1&request=soap.request.xsl"
        + "&response=soap.response.xsl&wsUsername=bits&wsPasswordSecret=ws-password";
    String route = """
        - route:
            from:
              uri: direct:partner-soapbill
              steps:
                - choice:
                    when:
                      - simple: "${header.mwOperation} == 'PAY'"
                        steps:
                          - to: "kamelet:mw-map?mapping=PAY.request"
                          - to: "%1$s&soapAction=urn:soapbill:v1:PayBill"
                          - to: "kamelet:mw-classify?mapping=PAY.response&definiteStatuses=200"
                      - simple: "${header.mwOperation} == 'INQUIRY'"
                        steps:
                          - to: "kamelet:mw-map?mapping=INQUIRY.request"
                          - to: "%1$s&soapAction=urn:soapbill:v1:FetchBill"
                          - to: "kamelet:mw-classify?mapping=INQUIRY.response&definiteStatuses=200"
                - to: "kamelet:mw-audit"
        """.formatted(call.formatted(port));
    return Map.of(
        "family", "billpay",
        "allowedHosts", List.of("localhost"),
        "timeoutMs", 3000,
        "route", route,
        "mappings", Map.of(
            "PAY.request", "{\"op\": \"PAY\", \"ref\": reference, \"bill\": creditParty.id, \"amount\": amount.major}",
            "INQUIRY.request", "{\"op\": \"INQUIRY\", \"bill\": creditParty.id}",
            "soap.request.xsl", REQUEST_XSL,
            "soap.response.xsl", RESPONSE_XSL,
            "PAY.response", "body.receipt ? {\"outcome\": \"SUCCESS\", \"partnerRef\": body.receipt}"
                + " : body.fault ? {\"outcome\": \"FAILED\", \"code\": body.code} : {\"outcome\": \"UNKNOWN\", \"reason\": \"MALFORMED_REPLY\"}",
            "INQUIRY.response", "body.amount ? {\"outcome\": \"SUCCESS\", \"partnerRef\": body.bill,"
                + " \"attributes\": {\"amount\": body.amount, \"dueDate\": body.due}}"
                + " : body.fault ? {\"outcome\": \"FAILED\", \"code\": body.code} : {\"outcome\": \"UNKNOWN\", \"reason\": \"MALFORMED_REPLY\"}"),
        "errorMap", Map.of(
            "3001", Map.of("suffix", "BILL_NOT_FOUND", "retryable", false),
            "3003", Map.of("suffix", "PAYMENT_REFUSED", "retryable", false)));
  }

  static String envelope(String body) {
    return "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body>" + body
        + "</soap:Body></soap:Envelope>";
  }

  static String fault(String code) {
    return envelope("<soap:Fault><faultcode>soap:Client</faultcode><faultstring>refused</faultstring>"
        + "<detail><s:error xmlns:s=\"" + NS + "\"><s:code>" + code + "</s:code></s:error></detail></soap:Fault>");
  }

  @Test
  void aConfigChangedRecordForASoapBillerServesAFetchAndAPayAndMapsAFault() throws Exception {
    BILLER.stubFor(post(urlEqualTo("/soapbill/v1")).withHeader("SOAPAction", containing("FetchBill"))
        .withRequestBody(containing("<s:FetchBill"))
        .withRequestBody(containing("<s:billNumber>S-123</s:billNumber>"))
        .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/xml")
            .withBody(envelope("<s:FetchBillResponse xmlns:s=\"" + NS + "\"><s:billNumber>S-123</s:billNumber>"
                + "<s:amountDue>412.50</s:amountDue><s:dueDate>2026-12-01</s:dueDate></s:FetchBillResponse>"))));
    BILLER.stubFor(post(urlEqualTo("/soapbill/v1")).withHeader("SOAPAction", containing("FetchBill"))
        .withRequestBody(containing("<s:billNumber>S-404</s:billNumber>"))
        .willReturn(aResponse().withStatus(500).withHeader("Content-Type", "text/xml").withBody(fault("3001"))));
    BILLER.stubFor(post(urlEqualTo("/soapbill/v1")).withHeader("SOAPAction", containing("PayBill"))
        .withRequestBody(containing("<s:PayBill"))
        .withRequestBody(containing("<s:billNumber>S-123</s:billNumber>"))
        .withRequestBody(containing("<s:amount>412.50</s:amount>"))
        .withRequestBody(containing("<s:reference>BITS"))
        .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/xml")
            .withBody(envelope("<s:PayBillResponse xmlns:s=\"" + NS + "\"><s:receipt>SB-777</s:receipt></s:PayBillResponse>"))));
    BILLER.stubFor(post(urlEqualTo("/soapbill/v1")).withHeader("SOAPAction", containing("PayBill"))
        .withRequestBody(containing("<s:PayBill"))
        .withRequestBody(containing("<s:billNumber>S-999</s:billNumber>"))
        .willReturn(aResponse().withStatus(500).withHeader("Content-Type", "text/xml").withBody(fault("3003"))));

    publishConfig("partner/soapbill", JSON.writeValueAsString(Map.of(
        "schemaVersion", 1, "configKey", "partner/soapbill", "configType", "PARTNER", "change", "UPSERT",
        "version", 1, "changedAt", "2026-11-18T10:00:00Z", "changedBy", "ops.maker1", "approvedBy", "ops.checker1",
        "value", soapbill(BILLER.port()))));
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (store.instance("soapbill").isEmpty() && System.nanoTime() < deadline) {
      Thread.sleep(100);
    }
    assertThat(store.instance("soapbill")).isPresent();

    BillView bill = fetch.fetch("SOAPWATER", "S-123");
    assertThat(bill.amount().valueMinor()).isEqualTo(41_250);
    assertThat(bill.dueDate()).isEqualTo("2026-12-01");
    BILLER.verify(1, postRequestedFor(urlEqualTo("/soapbill/v1"))
        .withHeader("SOAPAction", equalTo("\"urn:soapbill:v1:FetchBill\""))
        .withHeader("Content-Type", containing("text/xml"))
        .withHeader("mwReference", absent())
        .withHeader("RequestContext", absent())
        .withRequestBody(containing("<wsse:Username>bits</wsse:Username>"))
        .withRequestBody(containing(WS_PASSWORD))
        .withRequestBody(containing("<s:FetchBill")));

    assertThatThrownBy(() -> fetch.fetch("SOAPWATER", "S-404"))
        .isInstanceOfSatisfying(MwException.class, e -> assertThat(e.code()).isEqualTo("MW-PTR-SOAPBILL-BILL_NOT_FOUND"));

    PartnerReply paid = pay("S-123", 41_250);
    assertThat(paid.payload().outcome()).isEqualTo("SUCCESS");
    assertThat(paid.payload().partnerRef()).isEqualTo("SB-777");

    PartnerReply refused = pay("S-999", 10_000);
    assertThat(refused.payload().outcome()).isEqualTo("FAILED");
    assertThat(refused.payload().errorCode()).isEqualTo("MW-PTR-SOAPBILL-PAYMENT_REFUSED");
    BILLER.verify(2, postRequestedFor(urlEqualTo("/soapbill/v1")).withHeader("SOAPAction", containing("PayBill")));
  }

  private PartnerReply pay(String billNumber, long valueMinor) throws Exception {
    String txnId = BillpayFixtures.newTxnId();
    PartnerRequest base = BillpayFixtures.pay(txnId, billNumber, valueMinor);
    PartnerRequest request = new PartnerRequest(
        base.schemaVersion(), base.family(), UUID.randomUUID().toString(), txnId, base.correlationId(), base.issuedAt(),
        base.deadlineAt(),
        new PartnerRequest.Payload(
            "PAY", "BILL_PAY", new PartnerRequest.PartnerRef("soapbill", "BILL", "SOAPWATER"), "BITS-IGNORED",
            base.payload().amount(), base.payload().debitParty(), base.payload().creditParty(), null, null));
    publisher.publish("mw.partner.request.billpay.v1", txnId,
        new EventMetadata(request.correlationId(), txnId, request.requestId(), 1), request);
    List<ConsumerRecord<String, String>> replies = KafkaTestConsumer.read(
        TestInfrastructure.kafkaBootstrapServers(), "mw.partner.reply.v1", 1, Duration.ofSeconds(30), r -> txnId.equals(r.key()));
    assertThat(replies).hasSize(1);
    return JSON.readValue(replies.getFirst().value(), PartnerReply.class);
  }

  private static void publishConfig(String key, String value) throws Exception {
    Properties props = new Properties();
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, TestInfrastructure.kafkaBootstrapServers());
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
      producer.send(new ProducerRecord<>(PartnerConfigKafkaListener.TOPIC, key, value)).get();
    }
  }
}
