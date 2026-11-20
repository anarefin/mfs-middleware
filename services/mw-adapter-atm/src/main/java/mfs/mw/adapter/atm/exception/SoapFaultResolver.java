package mfs.mw.adapter.atm.exception;

import java.util.Locale;
import javax.xml.XMLConstants;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import mfs.mw.adapter.atm.controller.AtmSoap;
import mfs.mw.adapter.atm.dto.ErrorDetail;
import mfs.mw.adapter.atm.dto.ErrorEnvelope;
import mfs.mw.adapter.atm.filter.CorrelationIdFilter;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.ws.context.MessageContext;
import org.springframework.ws.server.EndpointExceptionResolver;
import org.springframework.ws.soap.SoapFault;
import org.springframework.ws.soap.SoapFaultDetail;
import org.springframework.ws.soap.SoapMessage;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Every failure of the inbound ATM SOAP API becomes a SOAP fault whose {@code detail} is the error
 * envelope (spec 5.3) in the ATM namespace (ticket 120a): a client fault for a 4xx code (bad
 * request, authentication, unknown atmTxnRef), a server fault for a 5xx one, which the switch may
 * send again. An unexpected exception is {@code MW-SYS-001}; its message stays in the log.
 */
@Component
public class SoapFaultResolver implements EndpointExceptionResolver, Ordered {

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE;
  }

  @Override
  public boolean resolveException(MessageContext messageContext, Object endpoint, Exception ex) {
    if (!(messageContext.getResponse() instanceof SoapMessage response)) {
      return false;
    }
    MwException error = unwrap(ex);
    if (error == null) {
      error = new MwException(ErrorCodes.SYS_INTERNAL, "Internal error");
    }
    if (!response.getSoapBody().hasFault()) {
      write(response, error);
    }
    return true;
  }

  public static void write(SoapMessage response, MwException error) {
    String message = error.getMessage() == null ? "Request failed" : error.getMessage();
    SoapFault fault = error.status().is5xxServerError()
        ? response.getSoapBody().addServerOrReceiverFault(message, Locale.ENGLISH)
        : response.getSoapBody().addClientOrSenderFault(message, Locale.ENGLISH);
    SoapFaultDetail detail = fault.addFaultDetail();
    try {
      TransformerFactory factory = TransformerFactory.newInstance();
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.newTransformer().transform(new DOMSource(errorElement(error)), detail.getResult());
    } catch (Exception e) {
      throw new IllegalStateException("SOAP fault detail could not be written", e);
    }
  }

  private static Element errorElement(MwException error) {
    Document doc = Xml.newDocument();
    Element root = doc.createElementNS(AtmSoap.NAMESPACE, "Error");
    doc.appendChild(root);
    text(root, "code", error.code());
    text(root, "message", error.getMessage());
    text(root, "txnId", error.txnId());
    text(root, "correlationId", MDC.get(CorrelationIdFilter.MDC_KEY));
    text(root, "retryable", Boolean.toString(error.retryable()));
    Element details = doc.createElementNS(AtmSoap.NAMESPACE, "details");
    root.appendChild(details);
    for (ErrorDetail d : error.details()) {
      Element one = doc.createElementNS(AtmSoap.NAMESPACE, "detail");
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
    Element child = parent.getOwnerDocument().createElementNS(AtmSoap.NAMESPACE, name);
    child.setTextContent(value);
    parent.appendChild(child);
  }

  private static MwException unwrap(Throwable ex) {
    Throwable current = ex;
    while (current != null) {
      if (current instanceof MwException mw) {
        return mw;
      }
      current = current.getCause();
    }
    return null;
  }

  /** Hardened DOM documents (no DTD, secure processing). */
  public static final class Xml {

    private Xml() {}

    public static Document newDocument() {
      try {
        javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().newDocument();
      } catch (javax.xml.parsers.ParserConfigurationException e) {
        throw new IllegalStateException(e);
      }
    }
  }
}
