package mfs.mw.simulators.service;

import static mfs.mw.simulators.util.SoapXml.escape;
import static mfs.mw.simulators.util.SoapXml.ok;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import mfs.mw.simulators.util.SoapXml;
import mfs.mw.simulators.util.SoapXml.SoapReply;
import org.springframework.stereotype.Service;
import org.w3c.dom.Element;

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
      operation = SoapXml.bodyElement(SoapXml.parse(request));
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

  private static SoapReply fault(String faultCode, String code, String reason) {
    return SoapXml.fault(faultCode, reason,
        "<s:error xmlns:s=\"" + NS + "\"><s:code>" + escape(code) + "</s:code></s:error>");
  }

  private static String text(Element parent, String name) {
    return SoapXml.text(parent, NS, name);
  }
}
