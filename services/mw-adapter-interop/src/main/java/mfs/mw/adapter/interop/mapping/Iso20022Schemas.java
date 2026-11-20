package mfs.mw.adapter.interop.mapping;

import java.io.IOException;
import java.io.StringReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.dom.DOMSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import mfs.mw.adapter.interop.exception.MwException;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * XSD validation of every NPSB message, in and out (ticket 121b), against the official ISO 20022
 * schemas under {@code src/main/resources/iso20022/}, compiled once. The document is parsed safely
 * first (no DOCTYPE, so no XXE or entity expansion; no external DTD or schema; secure processing;
 * at most {@value #MAX_BYTES} bytes), its root must be the expected message's {@code Document}, then
 * the schema decides. Every refusal is {@code MW-PTR-NPSB-INVALID_MESSAGE}.
 */
@Component
public class Iso20022Schemas {

  static final String INVALID = "MW-PTR-NPSB-INVALID_MESSAGE";
  static final int MAX_BYTES = 1024 * 1024;

  private final Map<IsoMessage, Schema> schemas = new EnumMap<>(IsoMessage.class);
  private final DocumentBuilderFactory documents;

  public Iso20022Schemas() {
    try {
      SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      // the XSDs are self-contained: no include / import of anything outside the jar
      factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "jar,file");
      for (IsoMessage message : IsoMessage.values()) {
        URL xsd = Iso20022Schemas.class.getClassLoader().getResource(message.xsd());
        if (xsd == null) {
          throw new IllegalStateException("missing XSD " + message.xsd());
        }
        schemas.put(message, factory.newSchema(xsd));
      }
      documents = DocumentBuilderFactory.newInstance();
      documents.setNamespaceAware(true);
      documents.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      documents.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      documents.setFeature("http://xml.org/sax/features/external-general-entities", false);
      documents.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      documents.setXIncludeAware(false);
      documents.setExpandEntityReferences(false);
      documents.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      documents.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    } catch (SAXException | ParserConfigurationException e) {
      throw new IllegalStateException("ISO 20022 schemas do not load", e);
    }
  }

  /** XML-DSig namespace of the enveloped signature mw-crypto adds and the switch sends. */
  public static final String DSIG_NS = "http://www.w3.org/2000/09/xmldsig#";

  /**
   * The document without its enveloped XML-DSig {@code Signature} (a child of {@code Document}), so the
   * ISO XSD, which has no place for it, can validate what is left. Only after the signature verified
   * (ticket 121b part 2). Parsed with the same safe settings.
   */
  public String unsigned(String xml) {
    if (xml == null || xml.isBlank() || xml.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
      throw new MwException(INVALID, "empty or too large document");
    }
    try {
      DocumentBuilder builder = documents.newDocumentBuilder();
      builder.setErrorHandler(null);
      Document doc = builder.parse(new InputSource(new StringReader(xml)));
      Element root = doc.getDocumentElement();
      org.w3c.dom.NodeList signatures = root.getElementsByTagNameNS(DSIG_NS, "Signature");
      for (int i = signatures.getLength() - 1; i >= 0; i--) {
        org.w3c.dom.Node signature = signatures.item(i);
        if (signature.getParentNode() == root) {
          root.removeChild(signature);
        }
      }
      javax.xml.transform.TransformerFactory tf = javax.xml.transform.TransformerFactory.newInstance();
      tf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
      java.io.StringWriter out = new java.io.StringWriter();
      tf.newTransformer().transform(new DOMSource(doc), new javax.xml.transform.stream.StreamResult(out));
      return out.toString();
    } catch (SAXException | IOException | ParserConfigurationException | javax.xml.transform.TransformerException e) {
      throw new MwException(INVALID, "document is not well-formed or safe XML", e);
    }
  }

  /** Parses and validates {@code xml} as {@code message}; the parsed document when valid. */
  public Document validate(IsoMessage message, String xml) {
    if (xml == null || xml.isBlank()) {
      throw new MwException(INVALID, "empty " + message.messageName());
    }
    if (xml.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
      throw new MwException(INVALID, message.messageName() + " larger than " + MAX_BYTES + " bytes");
    }
    Document doc;
    try {
      DocumentBuilder builder = documents.newDocumentBuilder();
      builder.setErrorHandler(null);
      doc = builder.parse(new InputSource(new StringReader(xml)));
    } catch (SAXException | IOException | ParserConfigurationException e) {
      throw new MwException(INVALID, message.messageName() + " is not well-formed or safe XML", e);
    }
    Element root = doc.getDocumentElement();
    if (!"Document".equals(root.getLocalName()) || !message.namespace().equals(root.getNamespaceURI())) {
      throw new MwException(INVALID, "not a " + message.messageName() + " Document");
    }
    try {
      Validator validator = schemas.get(message).newValidator();
      validator.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      validator.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      validator.validate(new DOMSource(doc));
    } catch (SAXException | IOException e) {
      // the validator's text quotes element values (names, accounts): never passed on, so never logged
      throw new MwException(INVALID, message.messageName() + " fails its XSD");
    }
    return doc;
  }
}
