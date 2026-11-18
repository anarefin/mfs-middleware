package mfs.mw.admin.api.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import mfs.mw.admin.api.config.PartnerRouteProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * The maker-side copy of the adapters' SOAP rules (ticket 85): {@code mw-soap-call} with an
 * approved address and stylesheets that are the record's {@code *.xsl} mappings or partner
 * resources; a stylesheet reaching outside itself ({@code document('http://…')}, an external
 * entity) is refused, as the adapters' loader refuses it.
 */
class SoapRouteValidatorTest {

  final PartnerRouteValidator validator =
      new PartnerRouteValidator(new PartnerRouteProperties(null), JsonMapper.builder().build());

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
  static final String CALL = "kamelet:mw-soap-call?address=https://soap.acme.example/ws&request=PAY.request.xsl"
      + "&response=PAY.response.xsl&soapAction=urn:acme:Pay&wsUsername=bits&wsPasswordSecret=ws-password";

  static String route(String call) {
    return """
        - route:
            from:
              uri: direct:partner-acme
              steps:
                - to: "kamelet:mw-map?mapping=PAY.request"
                - to: "%s"
                - to: "kamelet:mw-classify?mapping=PAY.response&definiteStatuses=200"
                - to: "kamelet:mw-audit"
        """.formatted(call);
  }

  static Map<String, String> mappings(Map<String, String> extra) {
    Map<String, String> m = new HashMap<>(Map.of(
        "PAY.request", "{\"a\": reference}", "PAY.response", "{\"outcome\": \"UNKNOWN\"}",
        "PAY.request.xsl", REQUEST_XSL, "PAY.response.xsl", RESPONSE_XSL));
    m.putAll(extra);
    return m;
  }

  List<PartnerRouteValidator.Problem> check(String call, Map<String, String> extra) {
    return validator.problems("acme", route(call), mappings(extra), List.of("soap.acme.example"));
  }

  @Test
  void aGoodSoapRouteHasNoProblems() {
    assertThat(validator.kamelets()).contains("mw-soap-call");
    assertThat(check(CALL, Map.of())).isEmpty();
  }

  @Test
  void anXsltUsingDocumentOnARemoteUriIsRefused() {
    String evil = RESPONSE_XSL.replace("true()", "string(document('http://evil.example/x'))");

    assertThat(check(CALL, Map.of("PAY.response.xsl", evil)))
        .anySatisfy(p -> assertThat(p.toString()).contains("PAY.response.xsl").contains("document()"));
  }

  @Test
  void anXsltWithAnExternalEntityIsRefused() {
    String evil = "<!DOCTYPE x [<!ENTITY e SYSTEM \"http://evil.example/x\">]>" + RESPONSE_XSL.replace("true()", "'&e;'");

    assertThat(check(CALL, Map.of("PAY.response.xsl", evil)))
        .anySatisfy(p -> assertThat(p.toString()).contains("DOCTYPE"));
  }

  /** Security review: run-time parsing, shadow attributes, static variables, hidden declarations. */
  @ParameterizedTest
  @ValueSource(strings = {
      "<xsl:template match='/'><xsl:copy-of select=\"parse-xml('&lt;!DOCTYPE a [&lt;!ENTITY e SYSTEM &quot;http://evil.example/&quot;&gt;]&gt;&lt;a&gt;&amp;e;&lt;/a&gt;')\"/></xsl:template>",
      "<xsl:template match='/'><xsl:copy-of select=\"parse-xml-fragment('&lt;a/&gt;')\"/></xsl:template>",
      "<xsl:template match='/'><xsl:value-of _select=\"{concat('environment-', 'variable')}('HOME')\"/></xsl:template>",
      "<xsl:variable name='f' static='yes' select=\"'environment-' || 'variable'\"/><xsl:template match='/'><a/></xsl:template>",
      "<xsl:template match='/'><xsl:value-of select=\"'&lt;!ENTITY e SYSTEM &quot;file:///etc/passwd&quot;&gt;'\"/></xsl:template>",
  })
  void aStylesheetThatParsesOrBuildsCodeAtCompileTimeIsRefused(String body) {
    String evil = "<xsl:stylesheet version=\"3.0\" xmlns:xsl=\"http://www.w3.org/1999/XSL/Transform\">" + body
        + "</xsl:stylesheet>";

    assertThat(check(CALL, Map.of("PAY.response.xsl", evil))).as(body)
        .anySatisfy(p -> assertThat(p.toString()).contains("PAY.response.xsl"));
  }

  @Test
  void aSoapAddressOutsideTheAllowedHostsIsRefused() {
    assertThat(check(CALL.replace("soap.acme.example", "evil.example"), Map.of()))
        .anySatisfy(p -> assertThat(p.toString()).contains("allowedHosts"));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "request=NOPE.xsl", "request=PAY.request", "request=classpath:other/evil.xsl", "request=file:/tmp/x.xsl",
      "soapAction=urn:acme%23Pay", "soapVersion=1.3", "wsTokenType=Plain"
  })
  void oddSoapParametersAreRefused(String param) {
    String key = param.substring(0, param.indexOf('='));
    String call = CALL.contains(key + "=") ? CALL.replaceFirst(key + "=[^&]*", param) : CALL + "&" + param;

    assertThat(check(call, Map.of())).as(param).isNotEmpty();
  }

  @Test
  void aWsSecurityUserWithoutASecretFieldIsRefused() {
    assertThat(check(CALL.replace("&wsPasswordSecret=ws-password", ""), Map.of()))
        .anySatisfy(p -> assertThat(p.toString()).contains("wsPasswordSecret"));
  }

  // --- review of 85: the adapters' Basic, mTLS and JSON/XML rules -----------------------------

  @Test
  void basicMtlsAndJsonBothWaysAreAccepted() {
    String call = CALL.replace("request=PAY.request.xsl&response=PAY.response.xsl", "request=json&response=json&namespace=urn:acme:v1")
        + "&auth=basic&userField=soap-user&secretField=soap-password&sslContextRef=acme-client";

    assertThat(check(call, Map.of())).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "&auth=basic&secretField=soap-password",
      "&auth=oauth2-cc&secretField=x",
      "&namespace=biller",
  })
  void aBasicWithoutItsFieldsAnotherSchemeOrABadNamespaceIsRefused(String params) {
    assertThat(check(CALL + params, Map.of())).as(params).isNotEmpty();
  }

  @Test
  void requestJsonNeedsANamespaceAndMtlsAnHttpsAddress() {
    assertThat(check(CALL.replace("request=PAY.request.xsl", "request=json"), Map.of()).toString()).contains("namespace");
    assertThat(check(CALL.replace("https://", "http://") + "&sslContextRef=acme-client", Map.of()).toString())
        .contains("https");
  }
}
