package mfs.mw.adapter.card.partner.mapping;

import org.w3c.dom.Element;

/**
 * How {@code mw-soap-call} turns the request JSON into the SOAP body element and the answer (or a
 * SOAP fault) back into the JSON {@code mw-classify} maps: an XSLT stylesheet of the record ({@link
 * CompiledStylesheet}) or the fixed JSON/XML convention ({@link JsonXmlMapping}, {@code
 * request=json} / {@code response=json}, for a JSONata mapping on either side). Every method throws
 * {@link IllegalArgumentException} for input it cannot map, naming no content.
 */
public interface SoapMapping {

  /** The SOAP body element made of {@code json}. */
  Element requestElement(String json);

  /** The JSON made of the SOAP body element {@code xml}. */
  String responseJson(String xml);

  /**
   * The JSON made of a SOAP fault: {@code code} and {@code subcode} are local names of the fault
   * code QNames, {@code detailXml} the fault's detail element (null or blank when there is none).
   */
  String faultJson(String code, String subcode, String reason, String detailXml);

  /** As {@link #faultJson(String, String, String, String)}, with the fault's parsed detail element. */
  String faultJson(String code, String subcode, String reason, Element detail);
}
