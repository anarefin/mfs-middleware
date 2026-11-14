package mfs.mw.crypto.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import mfs.mw.crypto.exception.CryptoErrors;
import org.w3c.dom.Document;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * XML parsing for XML-DSig (ticket 65) with XXE off: no DOCTYPE at all (so no external or internal
 * entity, no entity expansion), no external DTD, schema or XInclude, secure processing limits and a
 * maximum element depth. A document that does not parse is {@code MW-VAL-001} on {@code field},
 * without the parser's text (it could quote the document).
 */
public final class SafeXml {

  static final int MAX_DEPTH = 256;

  private SafeXml() {}

  public static Document parse(String field, byte[] xml) {
    try {
      DocumentBuilder b = factory().newDocumentBuilder();
      b.setEntityResolver((publicId, systemId) -> {
        throw new SAXException("external entity");
      });
      b.setErrorHandler(SILENT);
      return b.parse(new ByteArrayInputStream(xml));
    } catch (SAXException | java.io.IOException e) {
      throw CryptoErrors.invalidField(field, "not a well-formed XML document without DOCTYPE");
    } catch (ParserConfigurationException e) {
      throw new IllegalStateException(e);
    }
  }

  /** The document as UTF-8, with an XML declaration. */
  public static byte[] serialize(Document doc) {
    try {
      TransformerFactory tf = TransformerFactory.newInstance();
      tf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
      Transformer t = tf.newTransformer();
      t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
      doc.setXmlStandalone(true);
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      t.transform(new DOMSource(doc), new StreamResult(out));
      return out.toByteArray();
    } catch (TransformerException e) {
      throw new IllegalStateException("XML serialisation failed", e);
    }
  }

  private static DocumentBuilderFactory factory() throws ParserConfigurationException {
    DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
    f.setNamespaceAware(true);
    f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    f.setFeature("http://xml.org/sax/features/external-general-entities", false);
    f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
    f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    f.setAttribute("jdk.xml.maxElementDepth", MAX_DEPTH);
    f.setXIncludeAware(false);
    f.setExpandEntityReferences(false);
    return f;
  }

  private static final ErrorHandler SILENT =
      new ErrorHandler() {
        @Override
        public void warning(SAXParseException e) {}

        @Override
        public void error(SAXParseException e) throws SAXException {
          throw e;
        }

        @Override
        public void fatalError(SAXParseException e) throws SAXException {
          throw e;
        }
      };
}
