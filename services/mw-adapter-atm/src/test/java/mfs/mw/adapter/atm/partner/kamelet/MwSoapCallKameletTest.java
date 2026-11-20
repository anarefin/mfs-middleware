package mfs.mw.adapter.atm.partner.kamelet;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.net.ConnectException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.namespace.QName;
import javax.xml.transform.Source;
import javax.xml.transform.dom.DOMSource;
import mfs.mw.adapter.atm.partner.PartnerTestSeeds;
import mfs.mw.adapter.atm.partner.config.PartnerConfig;
import mfs.mw.adapter.atm.partner.mapping.CompiledStylesheet;
import mfs.mw.adapter.atm.partner.route.PartnerExchange;
import mfs.mw.adapter.atm.partner.route.PartnerRuntime;
import mfs.mw.adapter.atm.partner.route.PartnerSoapHeaderFilterStrategy;
import org.apache.camel.Exchange;
import org.apache.camel.Route;
import org.apache.camel.builder.AdviceWith;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.cxf.common.CxfPayload;
import org.apache.camel.component.mock.MockEndpoint;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.cxf.binding.soap.SoapFault;
import org.apache.cxf.binding.soap.SoapHeader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code mw-soap-call} alone (ticket 85): the Kamelet on a plain Camel context with its beans, the
 * {@code cxf} endpoints replaced by a {@link MockEndpoint} through {@link AdviceWith}. The request
 * JSON becomes the SOAP body with the UsernameToken and the SOAP action; the answer and a fault go
 * through the response stylesheet; a refused connect is NOT_SENT; a PAY is sent once, an INQUIRY
 * again after a failed connect.
 */
class MwSoapCallKameletTest {

  static final JsonMapper JSON = JsonMapper.builder().build();
  static final String NS = "urn:biller";
  static final String REQUEST_XSL = """
      <xsl:stylesheet version="3.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
          xmlns:fn="http://www.w3.org/2005/xpath-functions" exclude-result-prefixes="fn">
        <xsl:template match="/fn:map"><b:Pay xmlns:b="urn:biller"><xsl:value-of select="fn:string[@key='bill']"/></b:Pay></xsl:template>
      </xsl:stylesheet>
      """;
  static final String RESPONSE_XSL = """
      <xsl:stylesheet version="3.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform" xmlns:b="urn:biller">
        <xsl:output method="json"/>
        <xsl:template match="/b:PayResponse"><xsl:sequence select="map{'receipt': string(b:receipt)}"/></xsl:template>
        <xsl:template match="/*:fault"><xsl:sequence select="map{'fault': string(@code), 'code': string(*:detail/b:code)}"/></xsl:template>
      </xsl:stylesheet>
      """;

  static final java.time.Clock CLOCK =
      java.time.Clock.fixed(java.time.Instant.parse("2026-11-18T10:00:00Z"), java.time.ZoneOffset.UTC);

  DefaultCamelContext camel;
  MockEndpoint soap;
  MockEndpoint soapTls;

  @BeforeEach
  void camel() throws Exception {
    camel = new DefaultCamelContext();
    camel.getRegistry().bind("mwSoap", new MwSoapStep(JSON, CLOCK));
    camel.getRegistry().bind("mwAuth", new MwAuthStep(CLOCK, new mfs.mw.adapter.atm.partner.auth.OAuth2ClientCredentials(
        camel.createProducerTemplate(), JSON, CLOCK, java.time.Duration.ofSeconds(30))));
    camel.getRegistry().bind("mwHttp", new MwHttpStep(JSON));
    camel.getRegistry().bind("mwSoapHeaderFilter", new PartnerSoapHeaderFilterStrategy());
    camel.getRegistry().bind("mw-cb-acme", CircuitBreaker.class, CircuitBreaker.ofDefaults("acme"));
    camel.addRoutes(new RouteBuilder() {
      @Override
      public void configure() {
        from("direct:call").routeId("call")
            .to("kamelet:mw-soap-call?address=https://biller.example/ws&request=req.xsl&response=res.xsl"
                + "&soapAction=urn:biller:Pay&wsUsername=bits&wsPasswordSecret=ws-password"
                + "&partnerId=acme&timeoutMs=2000&connectTimeoutMs=500&readTimeoutMs=2500&bulkhead=5");
        from("direct:basic").routeId("basic")
            .to("kamelet:mw-soap-call?address=https://biller.example/ws&request=json&response=json&namespace=urn:biller"
                + "&auth=basic&userField=soap-user&secretField=soap-password&sslContextRef=acme-client"
                + "&partnerId=acme&timeoutMs=2000&connectTimeoutMs=500&readTimeoutMs=2500&bulkhead=5");
      }
    });
    camel.start();
    // the Kamelet routes exist once used; one call each creates them before they are advised
    for (String uri : List.of("direct:call", "direct:basic")) {
      try {
        camel.createProducerTemplate().request(uri, e -> { });
      } catch (RuntimeException e) {
        // no partner call yet: only the routes are wanted
      }
    }
    for (String kameletRoute : camel.getRoutes().stream().filter(Route::isCreatedByKamelet).map(Route::getRouteId)
        .filter(id -> id.startsWith("mw-soap-call")).toList()) {
      AdviceWith.adviceWith(camel, kameletRoute, a -> {
        a.weaveByType(org.apache.camel.model.ToDynamicDefinition.class).replace().to("mock:soap-tls");
        a.weaveByToUri("cxf:*").replace().to("mock:soap");
      });
    }
    soap = camel.getEndpoint("mock:soap", MockEndpoint.class);
    soapTls = camel.getEndpoint("mock:soap-tls", MockEndpoint.class);
  }

  @AfterEach
  void stop() {
    camel.stop();
  }

  Exchange call(String operation) {
    return call("direct:call", operation);
  }

  Exchange call(String uri, String operation) {
    PartnerConfig seed = PartnerTestSeeds.devSeed().getValue();
    PartnerRuntime runtime = new PartnerRuntime("acme", 1, seed, "partner-acme-v1", "direct:partner-acme-v1",
        Map.of(), Map.of("ws-password", "fake-acme-ws-password-for-tests", "soap-user", "fake-soap-user",
            "soap-password", "fake-soap-password"), List.of("biller.example"), Set.of("mw-soap-call"),
        Map.of("req.xsl", CompiledStylesheet.compile(REQUEST_XSL), "res.xsl", CompiledStylesheet.compile(RESPONSE_XSL)));
    return camel.createProducerTemplate().request(uri, e -> {
      e.setProperty(PartnerExchange.RUNTIME, runtime);
      e.setProperty(PartnerExchange.OPERATION_PROPERTY, operation);
      e.setProperty(PartnerExchange.REQUEST_BODY, "{\"bill\":\"B-1\"}");
      e.getMessage().setHeader(PartnerExchange.REFERENCE, "BITS1");
      e.getMessage().setBody("{\"bill\":\"B-1\"}");
    });
  }

  static CxfPayload<SoapHeader> answer(String xml) {
    Document doc = parse(xml);
    List<Source> body = new java.util.ArrayList<>(List.of(new DOMSource(doc.getDocumentElement())));
    return new CxfPayload<>(new java.util.ArrayList<>(), body, Map.of());
  }

  @Test
  @SuppressWarnings("unchecked")
  void theRequestJsonIsSentAsTheSoapBodyWithTheTokenAndTheAnswerComesBackAsJson() throws Exception {
    soap.whenAnyExchangeReceived(e -> e.getMessage().setBody(answer("<b:PayResponse xmlns:b=\"urn:biller\"><b:receipt>R-1</b:receipt></b:PayResponse>")));
    soap.expectedMessageCount(1);

    Exchange done = call("PAY");

    soap.assertIsSatisfied();
    Exchange sent = soap.getReceivedExchanges().getFirst();
    CxfPayload<SoapHeader> payload = sent.getMessage().getBody(CxfPayload.class);
    Element body = (Element) ((DOMSource) payload.getBodySources().getFirst()).getNode();
    assertThat(body.getNamespaceURI()).isEqualTo(NS);
    assertThat(body.getTextContent()).isEqualTo("B-1");
    Element security = (Element) payload.getHeaders().getFirst().getObject();
    assertThat(payload.getHeaders().getFirst().getName()).isEqualTo(new QName(MwSoapStep.WSSE, "Security"));
    assertThat(security.getElementsByTagNameNS(MwSoapStep.WSSE, "Username").item(0).getTextContent()).isEqualTo("bits");
    assertThat(security.getElementsByTagNameNS(MwSoapStep.WSSE, "Password").item(0).getTextContent())
        .isEqualTo("fake-acme-ws-password-for-tests");
    assertThat(sent.getMessage().getHeader("SOAPAction")).isEqualTo("urn:biller:Pay");
    assertThat(sent.getMessage().getHeader(MwSoapStep.REQUEST_CONTEXT, Map.class))
        .containsEntry(org.apache.cxf.message.Message.CONNECTION_TIMEOUT, 500L)
        .containsEntry(org.apache.cxf.message.Message.RECEIVE_TIMEOUT, 2500L);

    assertThat(done.getMessage().getBody(String.class)).isEqualTo("{\"receipt\":\"R-1\"}");
    assertThat(done.getProperty(PartnerExchange.RESPONSE_STATUS, Integer.class)).isEqualTo(200);
    assertThat(done.getProperty(PartnerExchange.RESPONSE_BODY, String.class)).isEqualTo("{\"receipt\":\"R-1\"}");
    assertThat(done.getMessage().getHeader(MwSoapStep.REQUEST_CONTEXT)).isNull();
  }

  @Test
  void aSoapFaultGoesThroughTheResponseStylesheet() throws Exception {
    soap.whenAnyExchangeReceived(e -> {
      Document doc = parse("<detail><b:code xmlns:b=\"urn:biller\">3001</b:code></detail>");
      SoapFault fault = new SoapFault("Bill not found", new QName("http://schemas.xmlsoap.org/soap/envelope/", "Client"));
      fault.setDetail(doc.getDocumentElement());
      throw fault;
    });

    Exchange done = call("PAY");

    assertThat(done.getMessage().getBody(String.class)).isEqualTo("{\"fault\":\"Client\",\"code\":\"3001\"}");
    assertThat(done.getProperty(PartnerExchange.FINAL, Boolean.class)).isFalse();
  }

  @Test
  void aRefusedConnectIsNotSentAndAPayIsNeverSentAgain() throws Exception {
    soap.whenAnyExchangeReceived(e -> {
      throw new org.apache.cxf.interceptor.Fault(new ConnectException("refused"));
    });
    soap.expectedMessageCount(1);

    Exchange done = call("PAY");

    soap.assertIsSatisfied();
    assertThat(JSON.readTree(done.getMessage().getBody(String.class)).get("outcome").asString()).isEqualTo("NOT_SENT");
  }

  @Test
  void anInquiryIsSentAgainAfterAFailedConnect() throws Exception {
    soap.whenAnyExchangeReceived(e -> {
      throw new org.apache.cxf.interceptor.Fault(new ConnectException("refused"));
    });
    soap.expectedMessageCount(2);

    call("INQUIRY");

    soap.assertIsSatisfied();
  }

  @Test
  void aTimeoutIsUnknown() throws Exception {
    soap.whenAnyExchangeReceived(e -> {
      throw new java.net.http.HttpTimeoutException("request timed out");
    });

    Exchange done = call("PAY");

    var result = JSON.readTree(done.getMessage().getBody(String.class));
    assertThat(result.get("outcome").asString()).isEqualTo("UNKNOWN");
    assertThat(result.get("reason").asString()).isEqualTo("TIMEOUT");
  }

  /** Review of 85: Basic through mw-auth-basic, mTLS through the named context, JSON/XML both ways. */
  @Test
  @SuppressWarnings("unchecked")
  void basicAndMtlsReuseTheHttpMechanismsAndJsonMapsBothWays() throws Exception {
    soapTls.whenAnyExchangeReceived(e -> e.getMessage().setBody(answer(
        "<b:PayResponse xmlns:b=\"urn:biller\"><b:receipt>R-2</b:receipt></b:PayResponse>")));
    soapTls.expectedMessageCount(1);
    soap.expectedMessageCount(0);

    Exchange done = camel.createProducerTemplate().request("direct:basic", e -> {
      e.setProperty(PartnerExchange.RUNTIME, new PartnerRuntime("acme", 1, PartnerTestSeeds.devSeed().getValue(),
          "partner-acme-v1", "direct:partner-acme-v1", Map.of(),
          Map.of("soap-user", "fake-soap-user", "soap-password", "fake-soap-password"), List.of("biller.example"),
          Set.of("mw-soap-call")));
      e.setProperty(PartnerExchange.OPERATION_PROPERTY, "PAY");
      e.setProperty(PartnerExchange.REQUEST_BODY, "{\"Pay\":{\"bill\":\"B-2\"}}");
      e.getMessage().setBody("{\"Pay\":{\"bill\":\"B-2\"}}");
    });

    soapTls.assertIsSatisfied();
    soap.assertIsSatisfied();
    Exchange sent = soapTls.getReceivedExchanges().getFirst();
    assertThat(sent.getMessage().getHeader(MwAuthStep.AUTHORIZATION)).isEqualTo("Basic " + java.util.Base64.getEncoder()
        .encodeToString("fake-soap-user:fake-soap-password".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    CxfPayload<SoapHeader> payload = sent.getMessage().getBody(CxfPayload.class);
    Element body = (Element) ((DOMSource) payload.getBodySources().getFirst()).getNode();
    assertThat(body.getNamespaceURI()).isEqualTo(NS);
    assertThat(body.getLocalName()).isEqualTo("Pay");
    assertThat(body.getTextContent()).isEqualTo("B-2");
    assertThat(done.getMessage().getBody(String.class)).isEqualTo("{\"PayResponse\":{\"receipt\":\"R-2\"}}");
  }

  /** The stub's answers as DOM (test data). */
  static Document parse(String xml) {
    try {
      return javax.xml.parsers.DocumentBuilderFactory.newDefaultNSInstance().newDocumentBuilder()
          .parse(new org.xml.sax.InputSource(new java.io.StringReader(xml)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
