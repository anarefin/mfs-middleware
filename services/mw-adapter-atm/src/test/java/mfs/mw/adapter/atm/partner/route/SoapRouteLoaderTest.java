package mfs.mw.adapter.atm.partner.route;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.resilience4j.bulkhead.BulkheadRegistry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import mfs.mw.adapter.atm.partner.PartnerTestSeeds;
import mfs.mw.adapter.atm.partner.auth.PartnerCredentials;
import mfs.mw.adapter.atm.partner.config.PartnerConfig;
import mfs.mw.adapter.atm.partner.config.PartnerConfigRefusedException;
import mfs.mw.adapter.atm.partner.config.PartnerEndpointPolicy;
import mfs.mw.adapter.atm.partner.config.PartnerProperties;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The route loader's rules for a SOAP partner (ticket 85): {@code mw-soap-call} with a static
 * address on the record's hosts, request and response stylesheets that are the record's {@code
 * *.xsl} mappings or {@code classpath:partner/xslt/} resources, a WS-Security password named by
 * secret field only; a stylesheet reaching outside itself is refused as a security rule.
 */
class SoapRouteLoaderTest {

  static final DefaultCamelContext CAMEL = new DefaultCamelContext();
  static PartnerRouteLoader loader;

  static final String REQUEST_XSL = """
      <xsl:stylesheet version="3.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
          xmlns:fn="http://www.w3.org/2005/xpath-functions" exclude-result-prefixes="fn">
        <xsl:template match="/fn:map"><b:Pay xmlns:b="urn:biller"><xsl:value-of select="fn:string[@key='a']"/></b:Pay></xsl:template>
      </xsl:stylesheet>
      """;
  static final String RESPONSE_XSL = """
      <xsl:stylesheet version="3.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
        <xsl:output method="json"/>
        <xsl:template match="/"><xsl:sequence select="map{'ok': true()}"/></xsl:template>
      </xsl:stylesheet>
      """;

  static final String CALL =
      "kamelet:mw-soap-call?address=http://localhost:8089/soap&request=PAY.request.xsl&response=PAY.response.xsl"
          + "&soapAction=urn:biller:Pay&wsUsername=bits&wsPasswordSecret=ws-password";

  @BeforeAll
  static void camel() throws Exception {
    CAMEL.addRoutes(new PartnerRouteConfiguration());
    CAMEL.start();
    PartnerCredentials credentials = mock(PartnerCredentials.class);
    when(credentials.read(anyString(), anyString())).thenReturn("fake-credential");
    loader = new PartnerRouteLoader(
        CAMEL, new PartnerEndpointPolicy(new PartnerProperties.Security(Map.of(), List.of("localhost"))),
        new KameletCatalogue(), credentials, BulkheadRegistry.ofDefaults(), PartnerTestSeeds.noTlsContexts());
  }

  @AfterAll
  static void stop() {
    CAMEL.stop();
  }

  static PartnerConfig record(String call, Map<String, String> extraMappings) {
    String route = """
        - route:
            from:
              uri: direct:partner-acme
              steps:
                - to: "kamelet:mw-map?mapping=PAY.request"
                - to: "%s"
                - to: "kamelet:mw-classify?mapping=PAY.response&definiteStatuses=200"
                - to: "kamelet:mw-audit"
        """.formatted(call);
    Map<String, String> mappings = new HashMap<>(Map.of(
        "PAY.request", "{\"a\": reference}",
        "PAY.response", "{\"outcome\": \"UNKNOWN\"}",
        "PAY.request.xsl", REQUEST_XSL,
        "PAY.response.xsl", RESPONSE_XSL));
    mappings.putAll(extraMappings);
    return new PartnerConfig(true, route, mappings, List.of("localhost"), "billpay", null, null, null,
        3000L, 1500L, null, null, null, null, null, null);
  }

  static PartnerRouteLoader.Loaded load(PartnerConfig config) {
    return loader.load("acme", 4, config);
  }

  @Test
  void aSoapRouteLoadsWithItsStylesheetsCompiledAndItsPasswordRead() {
    PartnerRouteLoader.Loaded loaded = load(record(CALL, Map.of()));

    assertThat(loaded.runtime().kamelets()).contains("mw-soap-call");
    assertThat(loaded.runtime().stylesheets()).containsOnlyKeys("PAY.request.xsl", "PAY.response.xsl");
    assertThat(loaded.runtime().mappings()).containsOnlyKeys("PAY.request", "PAY.response");
    assertThat(loaded.runtime().secrets()).containsEntry("ws-password", "fake-credential");
    assertThat(loaded.route().toString()).contains("partnerId=acme", "timeoutMs=1500", "connectTimeoutMs=3000");
  }

  @Test
  void aClasspathStylesheetUnderPartnerXsltLoads() {
    PartnerRouteLoader.Loaded loaded =
        load(record(CALL.replace("request=PAY.request.xsl", "request=classpath:partner/xslt/test-request.xsl"), Map.of()));

    assertThat(loaded.runtime().stylesheets()).containsKey("classpath:partner/xslt/test-request.xsl");
  }

  @Test
  void anXsltUsingDocumentOnARemoteUriIsRefusedAsASecurityRule() {
    String evil = RESPONSE_XSL.replace("map{'ok': true()}", "map{'ok': string(document('http://evil.example/x'))}");

    assertThatThrownBy(() -> load(record(CALL, Map.of("PAY.response.xsl", evil))))
        .isInstanceOfSatisfying(PartnerConfigRefusedException.class, e -> {
          assertThat(e.security()).isTrue();
          assertThat(e.getMessage()).contains("document()");
        });
  }

  @Test
  void anXsltWithAnExternalEntityIsRefusedAsASecurityRule() {
    String evil = "<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>" + RESPONSE_XSL.replace("true()", "'&e;'");

    assertThatThrownBy(() -> load(record(CALL, Map.of("PAY.response.xsl", evil))))
        .isInstanceOfSatisfying(PartnerConfigRefusedException.class, e -> {
          assertThat(e.security()).isTrue();
          assertThat(e.getMessage()).contains("DOCTYPE");
        });
  }

  @Test
  void aSoapAddressOutsideTheApprovedHostsIsRefused() {
    assertThatThrownBy(() -> load(record(CALL.replace("http://localhost:8089", "https://evil.example"), Map.of())))
        .isInstanceOfSatisfying(PartnerConfigRefusedException.class, e -> {
          assertThat(e.security()).isTrue();
          assertThat(e.getMessage()).contains("allowedHosts");
        });
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "request=NOPE.xsl",
      "request=PAY.request",
      "request=classpath:other/evil.xsl",
      "request=classpath:partner/xslt/../../application.yml",
      "request=file:/tmp/evil.xsl",
      "request=classpath:partner/xslt/missing.xsl",
  })
  void aStylesheetThatIsNotARecordXslOrAPartnerResourceIsRefused(String request) {
    assertThatThrownBy(() -> load(record(CALL.replace("request=PAY.request.xsl", request), Map.of())))
        .as(request)
        .isInstanceOf(PartnerConfigRefusedException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "soapAction=urn:biller%23Pay",
      "address=http://localhost:8089/soap%3Fx=1",
      "soapVersion=1.3",
      "wsTokenType=Plain",
  })
  void oddSoapParametersAreRefused(String param) {
    String key = param.substring(0, param.indexOf('='));
    String call = CALL.contains(key + "=") ? CALL.replaceFirst(key + "=[^&]*", param) : CALL + "&" + param;

    assertThatThrownBy(() -> load(record(call, Map.of()))).as(param).isInstanceOf(PartnerConfigRefusedException.class);
  }

  // --- review of 85: Basic, mTLS and JSON/XML ------------------------------------------------

  @Test
  void basicAuthReadsTheUserAndPasswordFields() {
    PartnerRouteLoader.Loaded loaded = load(record(CALL + "&auth=basic&userField=soap-user&secretField=soap-password", Map.of()));

    assertThat(loaded.runtime().secrets()).containsKeys("soap-user", "soap-password");
    assertThat(loaded.route().toString()).contains("auth=basic").doesNotContain("fake-credential");
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "&auth=basic&secretField=soap-password",
      "&auth=basic&userField=soap-user",
      "&auth=oauth2-cc&secretField=x",
      "&auth=hmac-sha256&secretField=x",
  })
  void basicNeedsBothFieldsAndNoOtherSchemeIsTaken(String params) {
    assertThatThrownBy(() -> load(record(CALL + params, Map.of()))).as(params).isInstanceOf(PartnerConfigRefusedException.class);
  }

  @Test
  void anMtlsContextMustBeThePartnersOwn() {
    assertThatThrownBy(() -> load(record(CALL.replace("http://localhost:8089", "https://localhost:8443") + "&sslContextRef=other-client",
            Map.of())))
        .isInstanceOfSatisfying(PartnerConfigRefusedException.class, e -> {
          assertThat(e.security()).isTrue();
          assertThat(e.getMessage()).contains("sslContextRef");
        });
  }

  @Test
  void jsonOnBothSidesNeedsNoStylesheetButANamespace() {
    String json = CALL.replace("request=PAY.request.xsl&response=PAY.response.xsl", "request=json&response=json&namespace=urn:biller:v1");

    PartnerRouteLoader.Loaded loaded = load(record(json, Map.of()));

    assertThat(loaded.runtime().stylesheets()).isEmpty();
    assertThat(loaded.route().toString()).contains("request=json", "namespace=urn:biller:v1");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "&namespace=biller", "&namespace=javascript:alert(1)"})
  void requestJsonWithoutAProperNamespaceIsRefused(String namespace) {
    String json = CALL.replace("request=PAY.request.xsl", "request=json") + namespace;

    assertThatThrownBy(() -> load(record(json, Map.of()))).as(namespace)
        .isInstanceOf(PartnerConfigRefusedException.class).hasMessageContaining("namespace");
  }

  @Test
  void aWsSecurityUserWithoutASecretFieldIsRefused() {
    assertThatThrownBy(() -> load(record(CALL.replace("&wsPasswordSecret=ws-password", ""), Map.of())))
        .isInstanceOf(PartnerConfigRefusedException.class)
        .hasMessageContaining("wsPasswordSecret");
  }
}
