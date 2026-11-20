package mfs.mw.app.gateway.exception;

import java.util.Locale;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import mfs.mw.app.gateway.controller.AppSoap;
import mfs.mw.app.gateway.dto.ErrorDetail;
import mfs.mw.app.gateway.dto.ErrorEnvelope;
import mfs.mw.app.gateway.filter.CorrelationIdFilter;
import org.slf4j.MDC;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.ws.soap.SoapBody;
import org.springframework.ws.soap.SoapFault;
import org.springframework.ws.soap.SoapFaultDetail;
import org.springframework.ws.soap.SoapMessage;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * A SOAP fault whose {@code detail} is the error envelope (spec 5.3) in the app SOAP namespace.
 * The fault string is the envelope message, which never carries a request value.
 */
public final class SoapFaults {

  private SoapFaults() {}

  /** The correlation id of this request: the HTTP header the filter kept, else the MDC. */
  public static String correlationId() {
    if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
      Object id = attrs.getRequest().getAttribute(CorrelationIdFilter.MDC_KEY);
      if (id != null) {
        return id.toString();
      }
    }
    return MDC.get(CorrelationIdFilter.MDC_KEY);
  }

  public static void write(SoapMessage response, MwException error) {
    SoapBody body = response.getSoapBody();
    String message = error.getMessage() == null ? "Request failed" : error.getMessage();
    SoapFault fault = error.status().is5xxServerError()
        ? body.addServerOrReceiverFault(message, Locale.ENGLISH)
        : body.addClientOrSenderFault(message, Locale.ENGLISH);
    SoapFaultDetail detail = fault.addFaultDetail();
    try {
      TransformerFactory factory = TransformerFactory.newInstance();
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.newTransformer().transform(new DOMSource(errorElement(error, correlationId())), detail.getResult());
    } catch (Exception e) {
      throw new IllegalStateException("SOAP fault detail could not be written", e);
    }
  }

  private static Element errorElement(MwException error, String correlationId) {
    Document doc = document();
    Element root = doc.createElementNS(AppSoap.NAMESPACE, "Error");
    doc.appendChild(root);
    text(root, "code", error.code());
    text(root, "message", error.getMessage());
    text(root, "messageBn", error.messageBn());
    text(root, "txnId", error.txnId());
    text(root, "correlationId", correlationId);
    text(root, "retryable", Boolean.toString(error.retryable()));
    Element details = doc.createElementNS(AppSoap.NAMESPACE, "details");
    root.appendChild(details);
    for (ErrorDetail d : error.details()) {
      Element one = doc.createElementNS(AppSoap.NAMESPACE, "detail");
      details.appendChild(one);
      text(one, "field", d.field());
      text(one, "issue", d.issue());
    }
    text(root, "timestamp", ErrorEnvelope.now());
    return root;
  }

  private static void text(Element parent, String name, String value) {
    if (value == null) {
      return;
    }
    Element child = parent.getOwnerDocument().createElementNS(AppSoap.NAMESPACE, name);
    child.setTextContent(value);
    parent.appendChild(child);
  }

  private static Document document() {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      return factory.newDocumentBuilder().newDocument();
    } catch (ParserConfigurationException e) {
      throw new IllegalStateException(e);
    }
  }
}
