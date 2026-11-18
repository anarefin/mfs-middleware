package mfs.mw.simulators.service;

import java.io.IOException;
import java.io.StringReader;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * The SOAP stub biller {@code soapbill} (ticket 85): the SOAP case of the partner engine, for the
 * adapters' {@code mw-soap-call} and the onboarding runbook. SOAP 1.1, namespace {@value #NS}:
 *
 * <ul>
 *   <li>{@code FetchBill {billNumber}} -> {@code FetchBillResponse {billNumber, amountDue, dueDate}},
 *       the amount 100 taka + the bill number's last three digits; a bill number ending {@code 404}
 *       is a fault with code {@code 3001} (not found);
 *   <li>{@code PayBill {billNumber, amount, reference}} -> {@code PayBillResponse {receipt}}, one
 *       receipt per reference (a repeat returns it again); a bill number ending {@code 999} is a
 *       fault with code {@code 3003} (refused);
 *   <li>{@code GetPayment {reference}} -> {@code GetPaymentResponse {status: PAID, receipt}}, or a
 *       fault with code {@code 3404} when no payment has that reference;
 *   <li>a fault {@code soap:Client} / {@code soap:Server} carries {@code <detail><s:error><s:code>};
 *       {@link #forceFault} answers every call with one fault until {@link #clearFault}.
 * </ul>
 *
 * State is in memory. Requests are parsed with DOCTYPE refused (a request with one is a Client fault).
 */
@Service
public class SoapBillerService {

  public static final String NS = "urn:soapbill:v1";
  static final String ENVELOPE = "http://schemas.xmlsoap.org/soap/envelope/";

  /** One SOAP answer: HTTP status (200, or 500 for a fault) and the envelope. */
  public record SoapReply(int status, String envelope) {}

  /** A fault forced on every call. */
  public record Fault(String faultCode, String code, String reason) {}

  private final Map<String, String> receipts = new ConcurrentHashMap<>();
  private final AtomicLong sequence = new AtomicLong();
  private volatile Fault forced;

  public void forceFault(Fault fault) {
    this.forced = new Fault(
        "Server".equals(fault.faultCode()) ? "Server" : "Client",
        fault.code() == null ? "9000" : fault.code(),
        fault.reason() == null ? "forced fault" : fault.reason());
  }

  public void clearFault() {
    this.forced = null;
  }

  public SoapReply handle(String request) {
    Element operation;
    try {
      operation = bodyElement(request);
    } catch (IllegalArgumentException e) {
      return fault("Client", "1000", "unreadable request");
    }
    Fault f = forced;
    if (f != null) {
      return fault(f.faultCode(), f.code(), f.reason());
    }
    if (operation == null || !NS.equals(operation.getNamespaceURI())) {
      return fault("Client", "1000", "unknown operation");
    }
    String bill = text(operation, "billNumber");
    return switch (operation.getLocalName()) {
      case "FetchBill" -> bill.endsWith("404")
          ? fault("Client", "3001", "bill not found")
          : ok("<s:FetchBillResponse xmlns:s=\"" + NS + "\"><s:billNumber>" + escape(bill) + "</s:billNumber>"
              + "<s:amountDue>" + due(bill) + "</s:amountDue><s:dueDate>" + LocalDate.of(2026, 12, 31)
              + "</s:dueDate></s:FetchBillResponse>");
      case "PayBill" -> {
        if (bill.endsWith("999")) {
          yield fault("Client", "3003", "payment refused by the biller");
        }
        String reference = text(operation, "reference");
        if (reference.isEmpty()) {
          yield fault("Client", "1001", "reference missing");
        }
        String receipt = receipts.computeIfAbsent(reference, r -> "SB-" + sequence.incrementAndGet());
        yield ok("<s:PayBillResponse xmlns:s=\"" + NS + "\"><s:receipt>" + receipt + "</s:receipt></s:PayBillResponse>");
      }
      case "GetPayment" -> {
        String receipt = receipts.get(text(operation, "reference"));
        yield receipt == null
            ? fault("Client", "3404", "payment not found")
            : ok("<s:GetPaymentResponse xmlns:s=\"" + NS + "\"><s:status>PAID</s:status><s:receipt>" + receipt
                + "</s:receipt></s:GetPaymentResponse>");
      }
      default -> fault("Client", "1000", "unknown operation");
    };
  }

  private static String due(String bill) {
    String digits = bill.replaceAll("\\D", "");
    int last = digits.isEmpty() ? 0 : Integer.parseInt(digits.substring(Math.max(0, digits.length() - 3)));
    return (100 + last) + ".00";
  }

  private static SoapReply ok(String body) {
    return new SoapReply(200, envelope(body));
  }

  private static SoapReply fault(String faultCode, String code, String reason) {
    return new SoapReply(500, envelope("<soap:Fault><faultcode>soap:" + faultCode + "</faultcode><faultstring>"
        + escape(reason) + "</faultstring><detail><s:error xmlns:s=\"" + NS + "\"><s:code>" + escape(code)
        + "</s:code></s:error></detail></soap:Fault>"));
  }

  private static String envelope(String body) {
    return "<soap:Envelope xmlns:soap=\"" + ENVELOPE + "\"><soap:Body>" + body + "</soap:Body></soap:Envelope>";
  }

  private static Element bodyElement(String xml) {
    Document doc;
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
      builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
        @Override
        public void fatalError(org.xml.sax.SAXParseException e) throws SAXException {
          throw e;
        }
      });
      doc = builder.parse(new InputSource(new StringReader(xml == null ? "" : xml)));
    } catch (ParserConfigurationException | SAXException | IOException e) {
      throw new IllegalArgumentException("unreadable SOAP request", e);
    }
    Element envelope = doc.getDocumentElement();
    for (Node n = envelope.getFirstChild(); n != null; n = n.getNextSibling()) {
      if (n instanceof Element e && "Body".equals(e.getLocalName())) {
        for (Node c = e.getFirstChild(); c != null; c = c.getNextSibling()) {
          if (c instanceof Element op) {
            return op;
          }
        }
      }
    }
    return null;
  }

  private static String text(Element parent, String name) {
    var nodes = parent.getElementsByTagNameNS(NS, name);
    return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().trim();
  }

  private static String escape(String s) {
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }
}
