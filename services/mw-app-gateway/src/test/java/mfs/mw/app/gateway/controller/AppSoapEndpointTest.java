package mfs.mw.app.gateway.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.ws.test.server.RequestCreators.withSoapEnvelope;

import org.mockito.ArgumentCaptor;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import mfs.mw.app.gateway.client.OrchestratorQuoteClient;
import mfs.mw.app.gateway.config.AppSoapSchemaInterceptor;
import mfs.mw.app.gateway.config.PreviewProperties;
import mfs.mw.app.gateway.config.WebServiceConfig;
import mfs.mw.app.gateway.dto.Amount;
import mfs.mw.app.gateway.dto.BalanceView;
import mfs.mw.app.gateway.dto.Party;
import mfs.mw.app.gateway.dto.Quote;
import mfs.mw.app.gateway.dto.SubmitResult;
import mfs.mw.app.gateway.dto.TransactionRequest;
import mfs.mw.app.gateway.dto.TransactionView;
import mfs.mw.app.gateway.exception.SoapFaultResolver;
import mfs.mw.app.gateway.mapper.AppSoapMapper;
import mfs.mw.app.gateway.repository.PreviewStore;
import mfs.mw.app.gateway.security.AppUserResolver;
import mfs.mw.app.gateway.service.BalanceService;
import mfs.mw.app.gateway.service.PreviewService;
import mfs.mw.app.gateway.service.TransactionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.ws.WebServiceMessage;
import org.springframework.ws.test.server.MockWebServiceClient;
import org.springframework.xml.transform.StringSource;

/**
 * The SOAP facade (ticket 108): preview and confirm call the same {@link PreviewService} as REST,
 * invalid XML is {@code MW-VAL-001} and does not echo the MSISDN, and a confirm without the
 * Idempotency-Key SOAP header is refused.
 */
@SpringBootTest(classes = AppSoapEndpointTest.Slice.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AppSoapEndpointTest {

  static final String NS = "xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\" xmlns:app=\""
      + AppSoap.NAMESPACE + "\"";
  static final String PAYEE = "01711000002";

  @Configuration
  @Import({
    WebServiceConfig.class, AppSoapEndpoint.class, AppSoapMapper.class, SoapFaultResolver.class,
    AppUserResolver.class, PreviewService.class
  })
  static class Slice {
    static final Map<String, PreviewStore.Stored> KEPT = new ConcurrentHashMap<>();

    @Bean
    PreviewStore previewStore() {
      return new PreviewStore(null) {
        @Override
        public void save(String id, Stored preview, Duration ttl) {
          KEPT.put(id, preview);
        }

        @Override
        public Optional<Stored> find(String id) {
          return Optional.ofNullable(KEPT.get(id));
        }
      };
    }

    @Bean
    PreviewProperties previewProperties() {
      return new PreviewProperties(Duration.ofMinutes(5), "test-only-preview-signing-key-0123456789");
    }

    @Bean
    Clock clock() {
      return Clock.fixed(Instant.parse("2026-11-20T06:00:00Z"), ZoneOffset.UTC);
    }

    @Bean
    jakarta.validation.Validator validator() {
      return jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator();
    }
  }

  @MockitoBean OrchestratorQuoteClient quotes;
  @MockitoBean TransactionService transactions;
  @MockitoBean mfs.mw.app.gateway.service.BillerService billers;
  @MockitoBean BalanceService balances;

  final ApplicationContext context;

  MockWebServiceClient client;

  AppSoapEndpointTest(ApplicationContext context) {
    this.context = context;
  }

  @BeforeEach
  void clientAndUser() {
    Slice.KEPT.clear();
    client = MockWebServiceClient.createClient(context);
    Jwt jwt = Jwt.withTokenValue("token")
        .header("alg", "none")
        .claim("msisdn", "01711000001")
        .claim("role", "customer")
        .claim("amr", List.of("pin"))
        .issuedAt(Instant.parse("2026-11-20T06:00:00Z"))
        .expiresAt(Instant.parse("2026-11-20T07:00:00Z"))
        .build();
    SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    when(quotes.quote(any())).thenReturn(new Quote(
        "P2P", new Quote.Money(100_000, "BDT"), new Quote.Money(1_850, "BDT"),
        new Quote.Money(241, "BDT"), new Quote.Money(0, "BDT"), new Quote.Money(101_850, "BDT"),
        new Quote.Limits(true, null, new Quote.Remaining(null, new Quote.Money(500_000, "BDT"), null, null, null)),
        new Party("WALLET", "0171****002", "R.", "CUSTOMER")));
    when(transactions.submit(any(), any(), any(), any())).thenReturn(SubmitResult.done(new TransactionView(
        "019a0000-0000-7000-8000-000000000001", "COMPLETED", "P2P",
        Party.wallet("01711000001", "CUSTOMER"), Party.wallet("0171****002", "CUSTOMER"),
        new Amount(100_000L, "BDT"), new Amount(1_850L, "BDT"), "CORE-1", null,
        Instant.parse("2026-11-20T06:00:01Z"), Instant.parse("2026-11-20T06:00:02Z"))));
  }

  @AfterEach
  void clearUser() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void previewThenConfirmUsesTheSameServicesAsRest() {
    String preview = exchange(envelope("", """
        <app:PreviewRequest>
          <app:group>send-money</app:group>
          <app:creditParty><app:id>%s</app:id></app:creditParty>
          <app:amount><app:valueMinor>100000</app:valueMinor><app:currency>BDT</app:currency></app:amount>
        </app:PreviewRequest>
        """.formatted(PAYEE)));
    assertThat(preview).doesNotContain("Fault");
    assertThat(text(preview, "fee")).contains("1850");
    assertThat(text(preview, "expiresAt")).isEqualTo("2026-11-20T06:05:00Z");
    String previewId = text(preview, "previewId");
    assertThat(previewId).isNotBlank();

    String confirm = exchange(envelope("<app:Idempotency-Key>soap-key-1</app:Idempotency-Key>", """
        <app:ConfirmRequest>
          <app:group>send-money</app:group>
          <app:previewId>%s</app:previewId>
          <app:creditParty><app:id>%s</app:id></app:creditParty>
          <app:amount><app:valueMinor>100000</app:valueMinor><app:currency>BDT</app:currency></app:amount>
          <app:encryptedPin><app:keyId>core-pin-1</app:keyId><app:ciphertext>Y2lwaGVy</app:ciphertext></app:encryptedPin>
        </app:ConfirmRequest>
        """.formatted(previewId, PAYEE)));

    assertThat(confirm).doesNotContain("Fault");
    assertThat(text(confirm, "txnId")).isEqualTo("019a0000-0000-7000-8000-000000000001");
    assertThat(text(confirm, "status")).isEqualTo("COMPLETED");
    verify(transactions).submit(any(), eq("soap-key-1"), any(), any());
  }

  /** Ticket 117c: transfer to bank names a linked account, as over REST. */
  @Test
  void aTransferToBankPreviewCarriesTheLinkedAccountId() {
    exchange(envelope("", """
        <app:PreviewRequest>
          <app:group>transfer-to-bank</app:group>
          <app:linkedAccountId>0b6f5a4e-3d2c-4b1a-9e8f-7d6c5b4a3f2e</app:linkedAccountId>
          <app:amount><app:valueMinor>150000</app:valueMinor><app:currency>BDT</app:currency></app:amount>
        </app:PreviewRequest>
        """));

    ArgumentCaptor<TransactionRequest> checked = ArgumentCaptor.forClass(TransactionRequest.class);
    verify(transactions).check(any(), checked.capture());
    assertThat(checked.getValue().txnType()).isEqualTo("W2B");
    assertThat(checked.getValue().linkedAccountId()).isEqualTo("0b6f5a4e-3d2c-4b1a-9e8f-7d6c5b4a3f2e");
  }

  /** XSD boolean {@code 1} is true (a confirmed duplicate must not stay false and be refused). */
  @Test
  void confirmDuplicateOfOneIsAcceptedAsTrue() {
    String preview = exchange(envelope("", """
        <app:PreviewRequest>
          <app:group>send-money</app:group>
          <app:creditParty><app:id>%s</app:id></app:creditParty>
          <app:amount><app:valueMinor>100000</app:valueMinor><app:currency>BDT</app:currency></app:amount>
        </app:PreviewRequest>
        """.formatted(PAYEE)));
    String previewId = text(preview, "previewId");

    exchange(envelope("<app:Idempotency-Key>soap-dup-1</app:Idempotency-Key>", """
        <app:ConfirmRequest>
          <app:group>send-money</app:group>
          <app:previewId>%s</app:previewId>
          <app:creditParty><app:id>%s</app:id></app:creditParty>
          <app:amount><app:valueMinor>100000</app:valueMinor><app:currency>BDT</app:currency></app:amount>
          <app:encryptedPin><app:keyId>core-pin-1</app:keyId><app:ciphertext>Y2lwaGVy</app:ciphertext></app:encryptedPin>
          <app:confirmDuplicate>1</app:confirmDuplicate>
        </app:ConfirmRequest>
        """.formatted(previewId, PAYEE)));

    ArgumentCaptor<TransactionRequest> sent = ArgumentCaptor.forClass(TransactionRequest.class);
    verify(transactions).submit(any(), eq("soap-dup-1"), sent.capture(), any());
    assertThat(sent.getValue().confirmDuplicate()).isTrue();
  }

  @Test
  void invalidXmlIsAValidationFaultAndDoesNotEchoTheMsisdn() {
    Logger log = (Logger) LoggerFactory.getLogger(AppSoapSchemaInterceptor.class);
    ListAppender<ILoggingEvent> events = new ListAppender<>();
    events.start();
    log.addAppender(events);
    try {
      String fault = exchange(envelope("", """
          <app:PreviewRequest>
            <app:group>send-money</app:group>
            <app:creditParty><app:id>%s</app:id></app:creditParty>
          </app:PreviewRequest>
          """.formatted(PAYEE)));
      assertThat(fault).contains("MW-VAL-001");
      assertThat(fault).contains("does not match the schema");
      assertThat(fault).doesNotContain(PAYEE);
      assertThat(events.list).allSatisfy(e -> assertThat(e.getFormattedMessage()).doesNotContain(PAYEE));
    } finally {
      log.detachAppender(events);
    }
  }

  @Test
  void aConfirmWithoutAnIdempotencyKeyIsAFault() {
    String fault = exchange(envelope("", """
        <app:ConfirmRequest>
          <app:group>send-money</app:group>
          <app:previewId>00000000-0000-0000-0000-000000000000.abcdefghijklmnopqrstuvwxyz0123456789ABCDE</app:previewId>
          <app:creditParty><app:id>01711000002</app:id></app:creditParty>
          <app:amount><app:valueMinor>100000</app:valueMinor><app:currency>BDT</app:currency></app:amount>
          <app:encryptedPin><app:keyId>core-pin-1</app:keyId><app:ciphertext>Y2lwaGVy</app:ciphertext></app:encryptedPin>
        </app:ConfirmRequest>
        """));
    assertThat(fault).contains("MW-VAL-001");
    assertThat(fault).contains("Idempotency-Key");
    assertThat(fault).contains("is required");
  }

  @Test
  void balanceAndStatusUseTheSameServices() {
    when(balances.check(any(), eq("bal-key"), any())).thenReturn(
        new BalanceView("019a0000-0000-7000-8000-000000000002", new Amount(5_000L, "BDT"),
            Instant.parse("2026-11-20T06:00:03Z")));
    String balance = exchange(envelope("<app:Idempotency-Key>bal-key</app:Idempotency-Key>", """
        <app:GetBalanceRequest>
          <app:encryptedPin><app:keyId>core-pin-1</app:keyId><app:ciphertext>Y2lwaGVy</app:ciphertext></app:encryptedPin>
        </app:GetBalanceRequest>
        """));
    assertThat(text(balance, "txnId")).isEqualTo("019a0000-0000-7000-8000-000000000002");
    assertThat(text(balance, "available")).contains("5000");

    when(transactions.find(any(), eq("019a0000-0000-7000-8000-000000000001"))).thenReturn(new TransactionView(
        "019a0000-0000-7000-8000-000000000001", "COMPLETED", "P2P", null, null,
        new Amount(100_000L, "BDT"), null, null, null, null, null));
    String status = exchange(envelope("", """
        <app:GetTransactionStatusRequest>
          <app:txnId>019a0000-0000-7000-8000-000000000001</app:txnId>
        </app:GetTransactionStatusRequest>
        """));
    assertThat(text(status, "status")).isEqualTo("COMPLETED");
  }

  private String exchange(String soap) {
    AtomicReference<String> body = new AtomicReference<>();
    client.sendRequest(withSoapEnvelope(new StringSource(soap)))
        .andExpect((request, response) -> body.set(xml(response)));
    return body.get();
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

  private static String envelope(String header, String body) {
    return """
        <soapenv:Envelope %s>
          <soapenv:Header>%s</soapenv:Header>
          <soapenv:Body>%s</soapenv:Body>
        </soapenv:Envelope>
        """.formatted(NS, header, body);
  }

  /** The text of the first element with this local name. */
  private static String text(String xml, String local) {
    int start = xml.indexOf("<" + local + ">");
    if (start < 0) {
      start = xml.indexOf(":" + local + ">");
      if (start < 0) {
        return "";
      }
      start = xml.lastIndexOf('<', start);
    }
    int from = xml.indexOf('>', start) + 1;
    int to = xml.indexOf("</", from);
    return xml.substring(from, to).strip();
  }
}
