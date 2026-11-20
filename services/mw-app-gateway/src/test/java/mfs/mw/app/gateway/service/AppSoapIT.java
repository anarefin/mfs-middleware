package mfs.mw.app.gateway.service;

import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.ws.test.server.RequestCreators.withSoapEnvelope;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.io.ByteArrayOutputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import mfs.mw.app.gateway.RedisInfrastructure;
import mfs.mw.app.gateway.TestInfrastructure;
import mfs.mw.app.gateway.TestTopics;
import mfs.mw.app.gateway.client.AuthClient;
import mfs.mw.app.gateway.controller.AppSoap;
import mfs.mw.app.gateway.dto.TxnAuthorisation;
import mfs.mw.app.gateway.messaging.OrchestratorStub;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.ws.WebServiceMessage;
import org.springframework.ws.test.server.MockWebServiceClient;
import org.springframework.ws.wsdl.wsdl11.DefaultWsdl11Definition;
import org.springframework.xml.transform.StringSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * SOAP and REST share one idempotency key (ticket 108). A SOAP confirm and a REST confirm of the
 * same preview under the same key publish one request and return one txnId.
 */
@SpringBootTest(
    properties = {
      "mw.app-gateway.reply-budget=3s",
      "mw.app-gateway.instance-id=it-gw-soap",
      "mw.app-gateway.consumers.enabled=true",
      "mw.app-gateway.idempotent-replay.enabled=true",
      "mw.kafka.retry.delays=1s,1s,1s"
    })
@AutoConfigureMockMvc
@Import({TestInfrastructure.class, RedisInfrastructure.class})
@DirtiesContext
class AppSoapIT {

  static final String PAYER = "01711000001";
  static final String PAYEE = "01711000002";
  static final String ASSERTION = "eyJhbGciOiJFUzI1NiJ9.eyJqdGkiOiJpdCJ9.c2lnbmF0dXJl";
  static final String NS = "xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\" xmlns:app=\""
      + AppSoap.NAMESPACE + "\"";
  static final String QUOTE = "{\"txnType\":\"P2P\",\"amount\":{\"valueMinor\":" + OrchestratorStub.COMPLETES
      + ",\"currency\":\"BDT\"},\"fee\":{\"valueMinor\":500,\"currency\":\"BDT\"},\"vat\":{\"valueMinor\":65,\"currency\":\"BDT\"},"
      + "\"commission\":{\"valueMinor\":0,\"currency\":\"BDT\"},\"total\":{\"valueMinor\":" + (OrchestratorStub.COMPLETES + 500)
      + ",\"currency\":\"BDT\"},\"limits\":{\"allowed\":true,\"remaining\":{}},"
      + "\"counterparty\":{\"type\":\"WALLET\",\"id\":\"0171****002\",\"name\":\"R.\",\"walletType\":\"CUSTOMER\"}}";

  static final WireMockServer HTTP = new WireMockServer(options().dynamicPort());

  static {
    HTTP.start();
  }

  private static OrchestratorStub orchestrator;

  private final MockMvcTester mvc;
  private final JsonMapper json;
  private final ApplicationContext context;
  private final ServletRegistrationBean<?> soapServlet;
  private final DefaultWsdl11Definition wsdl;

  private MockWebServiceClient soapClient;

  @MockitoBean AuthClient auth;

  @Autowired
  AppSoapIT(
      MockMvcTester mvc, JsonMapper json, ApplicationContext context,
      @Qualifier("appSoapServlet") ServletRegistrationBean<?> soapServlet, DefaultWsdl11Definition wsdl) {
    this.mvc = mvc;
    this.json = json;
    this.context = context;
    this.soapServlet = soapServlet;
    this.wsdl = wsdl;
  }

  @DynamicPropertySource
  static void orchestratorAndKeycloak(DynamicPropertyRegistry r) {
    r.add("mw.app-gateway.orchestrator.base-url", () -> "http://127.0.0.1:" + HTTP.port());
    r.add("mw.app-gateway.auth.token-uri", () -> "http://127.0.0.1:" + HTTP.port() + "/token");
  }

  @BeforeAll
  static void topicsAndOrchestrator() {
    TestTopics.ensure("mw.txn.request.v1", "mw.txn.status.v1");
    orchestrator = new OrchestratorStub(TestTopics.bootstrap());
    HTTP.stubFor(post(urlEqualTo("/token")).willReturn(okJson("{\"access_token\":\"tok-it\",\"expires_in\":300}")));
    HTTP.stubFor(post(urlEqualTo("/internal/v1/quotes"))
        .withRequestBody(equalToJson("{\"walletId\":\"" + PAYER + "\",\"txnType\":\"P2P\"}", true, true))
        .willReturn(okJson(QUOTE)));
  }

  @AfterAll
  static void stop() {
    orchestrator.close();
    HTTP.stop();
  }

  @BeforeEach
  void ready() {
    when(auth.authorise(any())).thenReturn(new TxnAuthorisation(ASSERTION, Instant.now().plusSeconds(60), false));
    TestTopics.awaitGroupStable("mw-app-gateway.reply.it-gw-soap", Duration.ofSeconds(60));
    soapClient = MockWebServiceClient.createClient(context);
    Jwt jwt = Jwt.withTokenValue("soap-it")
        .header("alg", "none")
        .claim("msisdn", PAYER)
        .claim("role", "customer")
        .claim("amr", List.of("pin"))
        .issuedAt(Instant.parse("2026-11-20T06:00:00Z"))
        .expiresAt(Instant.parse("2026-11-20T07:00:00Z"))
        .build();
    SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
  }

  @AfterEach
  void clearUser() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void aSoapConfirmAndARestConfirmWithTheSameKeyAreOneTransaction() throws Exception {
    String previewId = text(soap("", """
        <app:PreviewRequest>
          <app:group>send-money</app:group>
          <app:creditParty><app:id>%s</app:id></app:creditParty>
          <app:amount><app:valueMinor>%d</app:valueMinor><app:currency>BDT</app:currency></app:amount>
        </app:PreviewRequest>
        """.formatted(PAYEE, OrchestratorStub.COMPLETES)), "previewId");
    String key = "soap-" + UUID.randomUUID();

    String soapTxn = text(soap("<app:Idempotency-Key>" + key + "</app:Idempotency-Key>", """
        <app:ConfirmRequest>
          <app:group>send-money</app:group>
          <app:previewId>%s</app:previewId>
          <app:creditParty><app:id>%s</app:id></app:creditParty>
          <app:amount><app:valueMinor>%d</app:valueMinor><app:currency>BDT</app:currency></app:amount>
          <app:encryptedPin><app:keyId>core-pin-1</app:keyId><app:ciphertext>Y2lwaGVydGV4dA==</app:ciphertext></app:encryptedPin>
        </app:ConfirmRequest>
        """.formatted(previewId, PAYEE, OrchestratorStub.COMPLETES)), "txnId");

    MvcTestResult rest = mvc.post().uri("/app/v1/send-money/confirm")
        .with(jwt().jwt(j -> j.claim("msisdn", PAYER).claim("role", "customer").claim("amr", List.of("pin"))))
        .header("Idempotency-Key", key)
        .contentType(MediaType.APPLICATION_JSON)
        .content("""
            {"previewId":"%s","creditParty":{"id":"%s"},"amount":{"valueMinor":%d,"currency":"BDT"},
             "encryptedPin":{"keyId":"core-pin-1","ciphertext":"Y2lwaGVydGV4dA=="}}
            """.formatted(previewId, PAYEE, OrchestratorStub.COMPLETES))
        .exchange();

    assertThat(rest).hasStatus(200);
    assertThat(rest.getResponse().getContentAsString()).contains(soapTxn);
    assertThat(published(key)).isEqualTo(1);
  }

  @Test
  void theWsdlListsTheOperationsAndASoapPostWithoutATokenIs401() throws Exception {
    assertThat(soapServlet.getUrlMappings()).contains("/app/ws/v1", "/app/ws/v1/*");
    StringWriter out = new StringWriter();
    TransformerFactory.newInstance().newTransformer().transform(wsdl.getSource(), new StreamResult(out));
    assertThat(out.toString()).contains("Preview", "Confirm", "GetBalance", "GetTransactionStatus");
    assertThat(mvc.post().uri("/app/ws/v1").contentType(MediaType.TEXT_XML).content("<x/>"))
        .hasStatus(401)
        .bodyJson()
        .extractingPath("$.code")
        .isEqualTo("MW-AUTH-001");
  }

  private String soap(String header, String body) {
    AtomicReference<String> xml = new AtomicReference<>();
    soapClient.sendRequest(withSoapEnvelope(new StringSource("""
        <soapenv:Envelope %s>
          <soapenv:Header>%s</soapenv:Header>
          <soapenv:Body>%s</soapenv:Body>
        </soapenv:Envelope>
        """.formatted(NS, header, body))))
        .andExpect((request, response) -> xml.set(xml(response)));
    return xml.get();
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

  private long published(String key) {
    return orchestrator.requests().stream()
        .filter(r -> json.readTree(r.value()).path("idempotencyKey").asString().equals(key))
        .count();
  }

  private static String text(String xml, String local) {
    int marker = xml.indexOf(":" + local + ">");
    int start = marker < 0 ? xml.indexOf("<" + local + ">") : xml.lastIndexOf('<', marker);
    int from = xml.indexOf('>', start) + 1;
    return xml.substring(from, xml.indexOf("</", from)).strip();
  }
}
