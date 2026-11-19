package mfs.mw.simulators.util;

import java.io.IOException;
import java.io.StringReader;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * SOAP 1.1 helpers of the simulated SOAP partners ({@code soapbill}, the billagg SOAP front end): a
 * hardened parser (DOCTYPE refused, no external entities), the envelope's header and body elements,
 * namespaced child text, the envelope and fault writers and text escaping.
 */
public final class SoapXml {

  public static final String ENVELOPE = "http://schemas.xmlsoap.org/soap/envelope/";

  private static final Pattern WSSE_PASSWORD =
      Pattern.compile("(<([A-Za-z_][\\w.-]*:)?Password\\b[^>]*>)[^<]*(</([A-Za-z_][\\w.-]*:)?Password>)");

  /** One SOAP answer: HTTP status (200, or 500 for a fault) and the envelope. */
  public record SoapReply(int status, String envelope) {}

  private SoapXml() {}

  /** Parses a SOAP envelope; throws {@link IllegalArgumentException} when it is unreadable or has a DOCTYPE. */
  public static Document parse(String xml) {
    try {
      DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
      f.setNamespaceAware(true);
      f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      f.setFeature("http://xml.org/sax/features/external-general-entities", false);
      f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      f.setXIncludeAware(false);
      f.setExpandEntityReferences(false);
      DocumentBuilder builder = f.newDocumentBuilder();
      builder.setErrorHandler(new DefaultHandler() {
        @Override
        public void fatalError(SAXParseException e) throws SAXException {
          throw e;
        }
      });
      return builder.parse(new InputSource(new StringReader(xml == null ? "" : xml)));
    } catch (ParserConfigurationException | SAXException | IOException e) {
      throw new IllegalArgumentException("unreadable SOAP request", e);
    }
  }

  /** The first element in the envelope's {@code Body}, or null. */
  public static Element bodyElement(Document doc) {
    Element body = child(doc.getDocumentElement(), "Body");
    return body == null ? null : firstElement(body);
  }

  /** The envelope's {@code Header}, or null. */
  public static Element header(Document doc) {
    return child(doc.getDocumentElement(), "Header");
  }

  /** The first direct child element with this local name, or null. */
  public static Element child(Element parent, String localName) {
    if (parent == null) {
      return null;
    }
    for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
      if (n instanceof Element e && localName.equals(e.getLocalName())) {
        return e;
      }
    }
    return null;
  }

  /** The trimmed text of the first descendant {@code ns:name}, or "" when there is none. */
  public static String text(Element parent, String ns, String name) {
    var nodes = parent.getElementsByTagNameNS(ns, name);
    return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().trim();
  }

  public static String envelope(String body) {
    return "<soap:Envelope xmlns:soap=\"" + ENVELOPE + "\"><soap:Body>" + body + "</soap:Body></soap:Envelope>";
  }

  public static SoapReply ok(String body) {
    return new SoapReply(200, envelope(body));
  }

  /** A fault (HTTP 500): {@code faultCode} Client or Server, {@code detail} the partner's own XML. */
  public static SoapReply fault(String faultCode, String reason, String detail) {
    return new SoapReply(500, envelope("<soap:Fault><faultcode>soap:" + faultCode + "</faultcode><faultstring>"
        + escape(reason) + "</faultstring><detail>" + detail + "</detail></soap:Fault>"));
  }

  public static String escape(String s) {
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  /** The body with the text of every WS-Security {@code Password} element blanked (for recordings). */
  public static String redactPasswords(String xml) {
    return xml == null || !xml.contains("Password") ? xml : WSSE_PASSWORD.matcher(xml).replaceAll("$1***$3");
  }

  private static Element firstElement(Element parent) {
    for (Node c = parent.getFirstChild(); c != null; c = c.getNextSibling()) {
      if (c instanceof Element op) {
        return op;
      }
    }
    return null;
  }
}
