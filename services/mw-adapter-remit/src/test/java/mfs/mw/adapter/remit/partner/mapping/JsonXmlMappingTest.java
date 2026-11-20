package mfs.mw.adapter.remit.partner.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Element;
import tools.jackson.databind.json.JsonMapper;

/**
 * JSONata to XML and back for {@code mw-soap-call} (review of ticket 85): {@code request=json}
 * writes the JSON {@code mw-map}'s JSONata mapping made as the SOAP body element, by one fixed
 * convention (a key is an element in the call's namespace, {@code @name} an attribute, an array
 * repeats the element, a value is text, escaped by the DOM); {@code response=json} reads the SOAP
 * body (or a fault) back into JSON for {@code mw-classify}'s JSONata mapping. Built with the DOM,
 * never by string concatenation, so a value can never become markup.
 */
class JsonXmlMappingTest {

  static final JsonMapper JSON = JsonMapper.builder().build();
  final JsonXmlMapping mapping = new JsonXmlMapping("urn:biller:v1", JSON);

  @Test
  void theRequestJsonBecomesTheBodyElementInTheCallsNamespace() {
    Element pay = mapping.requestElement(
        "{\"PayBill\":{\"@version\":\"2\",\"billNumber\":\"77001234\",\"amount\":\"500.00\",\"line\":[\"a\",\"b\"],\"memo\":null}}");

    assertThat(pay.getNamespaceURI()).isEqualTo("urn:biller:v1");
    assertThat(pay.getLocalName()).isEqualTo("PayBill");
    assertThat(pay.getAttribute("version")).isEqualTo("2");
    assertThat(pay.getElementsByTagNameNS("urn:biller:v1", "billNumber").item(0).getTextContent()).isEqualTo("77001234");
    assertThat(pay.getElementsByTagNameNS("urn:biller:v1", "line").getLength()).isEqualTo(2);
    assertThat(pay.getElementsByTagNameNS("urn:biller:v1", "memo").item(0).getTextContent()).isEmpty();
  }

  @Test
  void aValueWithMarkupStaysText() {
    Element pay = mapping.requestElement("{\"Pay\":{\"ref\":\"</ref><evil/>&amp;\"}}");

    assertThat(pay.getElementsByTagNameNS("urn:biller:v1", "evil").getLength()).isZero();
    assertThat(pay.getElementsByTagNameNS("urn:biller:v1", "ref").item(0).getTextContent()).isEqualTo("</ref><evil/>&amp;");
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "{\"a\":1,\"b\":2}",            // two roots
      "[1]",                          // no root element
      "{\"x:y\":\"1\"}",              // a prefixed name
      "{\"1a\":\"1\"}",               // not an XML name
      "{\"Pay\":{\"@a\":{\"b\":1}}}", // an attribute that is no value
      "not json",
  })
  void aJsonThatIsNoSingleElementIsRefused(String json) {
    assertThatThrownBy(() -> mapping.requestElement(json)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void tooDeepAJsonIsRefused() {
    String deep = "{\"a\":".repeat(40) + "1" + "}".repeat(40);
    assertThatThrownBy(() -> mapping.requestElement(deep)).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("deep");
  }

  @Test
  void theAnswerBodyComesBackAsJson() {
    String json = mapping.responseJson(
        "<s:PayBillResponse xmlns:s=\"urn:biller:v1\" status=\"OK\"><s:receipt>R-1</s:receipt>"
            + "<s:item>1</s:item><s:item>2</s:item><s:empty/></s:PayBillResponse>");

    assertThat(JSON.readTree(json)).isEqualTo(JSON.readTree(
        "{\"PayBillResponse\":{\"@status\":\"OK\",\"receipt\":\"R-1\",\"item\":[\"1\",\"2\"],\"empty\":\"\"}}"));
  }

  @Test
  void anAnswerWithADoctypeIsRefused() {
    assertThatThrownBy(() -> mapping.responseJson("<!DOCTYPE r [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><r>&x;</r>"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aFaultComesBackInTheFaultShape() {
    String json = mapping.faultJson("Client", "", "Bill not found", "<detail><b:code xmlns:b=\"urn:biller:v1\">3001</b:code></detail>");

    assertThat(JSON.readTree(json)).isEqualTo(JSON.readTree(
        "{\"fault\":{\"@code\":\"Client\",\"@subcode\":\"\",\"@reason\":\"Bill not found\",\"detail\":{\"code\":\"3001\"}}}"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"urn:biller:v1", "http://biller.example/ws/v1", "https://biller.example:8443/ns"})
  void aNamespaceIsAUrnOrAnHttpUri(String ns) {
    assertThat(JsonXmlMapping.validNamespace(ns)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "biller", "javascript:alert(1)", "urn:a b", "http://x/%41", "http://x/?a=1"})
  void anythingElseIsNoNamespace(String ns) {
    assertThat(JsonXmlMapping.validNamespace(ns)).isFalse();
  }
}
