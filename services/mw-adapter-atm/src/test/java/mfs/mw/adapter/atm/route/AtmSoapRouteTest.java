package mfs.mw.adapter.atm.route;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.ws.test.server.RequestCreators.withSoapEnvelope;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.ByteArrayOutputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import mfs.mw.adapter.atm.config.AtmInboundProperties;
import mfs.mw.adapter.atm.config.AtmSchemaInterceptor;
import mfs.mw.adapter.atm.config.AtmWebServiceConfig;
import mfs.mw.adapter.atm.controller.AtmSoap;
import mfs.mw.adapter.atm.controller.AtmSoapEndpoint;
import mfs.mw.adapter.atm.exception.SoapFaultResolver;
import mfs.mw.adapter.atm.repository.AtmInboundStore;
import mfs.mw.adapter.atm.security.WsSecurityInterceptor;
import mfs.mw.adapter.atm.service.AtmInboundService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.ws.WebServiceMessage;
import org.springframework.ws.test.server.MockWebServiceClient;
import org.springframework.ws.wsdl.wsdl11.DefaultWsdl11Definition;
import org.springframework.xml.transform.StringSource;

/**
 * Inbound UCB ATM SOAP (ticket 120a, FRS UC-17, EXT-16, PLT-025, PLT-050): the four operations,
 * a repeated call answered once, an unknown cash-out, a WS-Security failure and a schema fault.
 * The one-time token never appears in the answer or the log (FL-11).
 */
@SpringBootTest(classes = AtmSoapRouteTest.Slice.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AtmSoapRouteTest {

  static final String TOKEN = "654321";
  static final String MSISDN = "01711000001";
  static final String BAD_MSISDN = "01111000001";
  static final String PASSWORD = "test-only-ucbatm-ws-password";
  static final String NS = "xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\""
      + " xmlns:atm=\"" + AtmSoap.NAMESPACE + "\""
      + " xmlns:wsse=\"" + AtmSoap.WSSE_NS + "\"";

  @Configuration
  @Import({
    AtmWebServiceConfig.class, AtmSoapEndpoint.class, AtmInboundService.class, SoapFaultResolver.class
  })
  static class Slice {
    @Bean
    AtmInboundStore atmInboundStore() {
      return new Memory();
    }

    @Bean
    AtmInboundProperties atmInboundProperties() {
      return new AtmInboundProperties("ucbatm", "ucbatm", PASSWORD);
    }

    @Bean
    Clock clock() {
      return Clock.fixed(Instant.parse("2026-11-20T12:00:00Z"), ZoneOffset.UTC);
    }
  }

  /** The idempotency of (operation, atmTxnRef) without a database. */
  static final class Memory implements AtmInboundStore {
    private final ConcurrentHashMap<String, Row> rows = new ConcurrentHashMap<>();

    @Override
    public boolean insert(String operation, String atmTxnRef, String txnId, String inboundId, Instant at) {
      return rows.putIfAbsent(operation + "\n" + atmTxnRef, new Row(txnId, inboundId)) == null;
    }

    @Override
    public Optional<Row> find(String operation, String atmTxnRef) {
      return Optional.ofNullable(rows.get(operation + "\n" + atmTxnRef));
    }
  }

  final ApplicationContext context;
  final DefaultWsdl11Definition wsdl;

  MockWebServiceClient client;

  AtmSoapRouteTest(ApplicationContext context, @Qualifier("atm") DefaultWsdl11Definition wsdl) {
    this.context = context;
    this.wsdl = wsdl;
  }

  @BeforeEach
  void client() {
    client = MockWebServiceClient.createClient(context);
  }

  @Test
  void verifyConfirmReversalAndARepeatKeepOneTxnAndDropTheToken() {
    ListAppender<ILoggingEvent> events = listen(
        AtmInboundService.class, AtmSoapEndpoint.class, WsSecurityInterceptor.class, AtmSchemaInterceptor.class);
    try {
      String first = exchange(verify("ATM-1", MSISDN, TOKEN));
      assertThat(first).doesNotContain("Fault").doesNotContain(TOKEN).doesNotContain(MSISDN);
      assertThat(text(first, "status")).isEqualTo("ACCEPTED");
      assertThat(text(first, "atmTxnRef")).isEqualTo("ATM-1");
      String txnId = text(first, "txnId");
      assertThat(txnId).isNotBlank();

      String again = exchange(verify("ATM-1", MSISDN, TOKEN));
      assertThat(text(again, "txnId")).isEqualTo(txnId);
      assertThat(again).doesNotContain(TOKEN);

      String confirm = exchange(named("ConfirmDispenseRequest", "ATM-1"));
      assertThat(confirm).doesNotContain("Fault");
      assertThat(text(confirm, "txnId")).isEqualTo(txnId);

      String reversal = exchange(reversal("ATM-1", "JAM"));
      assertThat(reversal).doesNotContain("Fault");
      assertThat(text(reversal, "txnId")).isEqualTo(txnId);

      String reversalAgain = exchange(reversal("ATM-1", "JAM"));
      assertThat(text(reversalAgain, "txnId")).isEqualTo(txnId);

      assertThat(events.list).allSatisfy(e -> {
        assertThat(e.getFormattedMessage()).doesNotContain(TOKEN).doesNotContain(MSISDN);
        assertThat(String.valueOf(e.getKeyValuePairs())).doesNotContain(TOKEN).doesNotContain(MSISDN);
      });
    } finally {
      detach(events);
    }
  }

  @Test
  void cashInIsAcceptedOnItsOwnTxn() {
    String body = exchange(cashIn("ATM-IN-1", MSISDN));
    assertThat(body).doesNotContain("Fault").doesNotContain(MSISDN);
    assertThat(text(body, "status")).isEqualTo("ACCEPTED");
    assertThat(text(body, "txnId")).isNotBlank();
  }

  @Test
  void anUnknownCashOutIsAClientFault() {
    String fault = exchange(named("ConfirmDispenseRequest", "ATM-MISSING"));
    assertThat(fault).contains("MW-VAL-ATM_UNKNOWN_TXN");
    assertThat(fault).contains("Unknown ATM transaction");
    assertThat(fault).contains("Client");
  }

  @Test
  void aMissingUsernameTokenIsAnAuthFaultAndDoesNotEchoTheToken() {
    String fault = exchange(envelope("", verifyBody("ATM-2", MSISDN, TOKEN)));
    assertThat(fault).contains("MW-AUTH-001");
    assertThat(fault).doesNotContain(TOKEN).doesNotContain(PASSWORD);
  }

  @Test
  void aSchemaFailureIsAValidationFaultAndDoesNotEchoTheBody() {
    ListAppender<ILoggingEvent> events = listen(AtmSchemaInterceptor.class);
    try {
      String fault = exchange(verify("ATM-3", BAD_MSISDN, TOKEN));
      assertThat(fault).contains("MW-VAL-001");
      assertThat(fault).contains("does not match the schema");
      assertThat(fault).doesNotContain(TOKEN).doesNotContain(BAD_MSISDN);
      assertThat(events.list).allSatisfy(e ->
          assertThat(e.getFormattedMessage()).doesNotContain(TOKEN).doesNotContain(BAD_MSISDN));
    } finally {
      detach(events);
    }
  }

  @Test
  void thePublishedWsdlNamesTheFourOperations() throws Exception {
    StringWriter out = new StringWriter();
    TransformerFactory factory = TransformerFactory.newInstance();
    factory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
    factory.newTransformer().transform(wsdl.getSource(), new StreamResult(out));
    String doc = out.toString();
    assertThat(doc).contains("VerifyTokenAndDebit", "ConfirmDispense", "ReversalAdvice", "CashIn");
    assertThat(doc).contains(AtmSoap.NAMESPACE);
  }

  private String exchange(String soap) {
    AtomicReference<String> body = new AtomicReference<>();
    client.sendRequest(withSoapEnvelope(new StringSource(soap)))
        .andExpect((request, response) -> body.set(xml(response)));
    return body.get();
  }

  private static String verify(String ref, String msisdn, String token) {
    return envelope(security(), verifyBody(ref, msisdn, token));
  }

  private static String verifyBody(String ref, String msisdn, String token) {
    return """
        <atm:VerifyTokenAndDebitRequest>
          <atm:atmTxnRef>%s</atm:atmTxnRef>
          <atm:atmId>UCB-DHK-01</atm:atmId>
          <atm:msisdn>%s</atm:msisdn>
          <atm:token>%s</atm:token>
          <atm:amountMinor>50000</atm:amountMinor>
        </atm:VerifyTokenAndDebitRequest>
        """.formatted(ref, msisdn, token);
  }

  private static String cashIn(String ref, String msisdn) {
    return envelope(security(), """
        <atm:CashInRequest>
          <atm:atmTxnRef>%s</atm:atmTxnRef>
          <atm:atmId>UCB-DHK-01</atm:atmId>
          <atm:msisdn>%s</atm:msisdn>
          <atm:amountMinor>25000</atm:amountMinor>
        </atm:CashInRequest>
        """.formatted(ref, msisdn));
  }

  private static String named(String operation, String ref) {
    return envelope(security(), """
        <atm:%s>
          <atm:atmTxnRef>%s</atm:atmTxnRef>
          <atm:atmId>UCB-DHK-01</atm:atmId>
        </atm:%s>
        """.formatted(operation, ref, operation));
  }

  private static String reversal(String ref, String reason) {
    return envelope(security(), """
        <atm:ReversalAdviceRequest>
          <atm:atmTxnRef>%s</atm:atmTxnRef>
          <atm:atmId>UCB-DHK-01</atm:atmId>
          <atm:reasonCode>%s</atm:reasonCode>
        </atm:ReversalAdviceRequest>
        """.formatted(ref, reason));
  }

  private static String security() {
    return """
        <wsse:Security>
          <wsse:UsernameToken>
            <wsse:Username>ucbatm</wsse:Username>
            <wsse:Password Type="%s">%s</wsse:Password>
          </wsse:UsernameToken>
        </wsse:Security>
        """.formatted(AtmSoap.PASSWORD_TEXT, PASSWORD);
  }

  private static String envelope(String header, String body) {
    return """
        <soapenv:Envelope %s>
          <soapenv:Header>%s</soapenv:Header>
          <soapenv:Body>%s</soapenv:Body>
        </soapenv:Envelope>
        """.formatted(NS, header, body);
  }

  private static String xml(WebServiceMessage message) {
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      message.writeTo(out);
      return out.toString(StandardCharsets.UTF_8);
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String text(String xml, String localName) {
    String open = ">" ;
    int from = xml.indexOf(":" + localName + ">");
    if (from < 0) {
      from = xml.indexOf("<" + localName + ">");
      if (from < 0) {
        return "";
      }
      from = xml.indexOf(open, from) + 1;
    } else {
      from = xml.indexOf(open, from) + 1;
    }
    int to = xml.indexOf('<', from);
    return to < 0 ? "" : xml.substring(from, to);
  }

  private static ListAppender<ILoggingEvent> listen(Class<?>... types) {
    ListAppender<ILoggingEvent> events = new ListAppender<>();
    events.start();
    for (Class<?> type : types) {
      ((Logger) LoggerFactory.getLogger(type)).addAppender(events);
    }
    return events;
  }

  private static void detach(ListAppender<ILoggingEvent> events) {
    for (Logger logger : List.of(
        (Logger) LoggerFactory.getLogger(AtmInboundService.class),
        (Logger) LoggerFactory.getLogger(AtmSoapEndpoint.class),
        (Logger) LoggerFactory.getLogger(WsSecurityInterceptor.class),
        (Logger) LoggerFactory.getLogger(AtmSchemaInterceptor.class))) {
      logger.detachAppender(events);
    }
  }
}
