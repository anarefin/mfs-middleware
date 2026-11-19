package mfs.mw.simulators.service;

import static mfs.mw.simulators.util.SoapXml.escape;

import jakarta.validation.Validator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import mfs.mw.simulators.dto.BillFetchRequest;
import mfs.mw.simulators.dto.BillPaymentRequest;
import mfs.mw.simulators.dto.BillPaymentResponse;
import mfs.mw.simulators.dto.BillResponse;
import mfs.mw.simulators.dto.Biller;
import mfs.mw.simulators.dto.Biller.Protocol;
import mfs.mw.simulators.dto.SimReply;
import mfs.mw.simulators.mapper.BillAggregatorMapper;
import mfs.mw.simulators.util.SoapXml;
import mfs.mw.simulators.util.SoapXml.SoapReply;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * The billagg SOAP front end ({@code POST /billagg/v1/soap}, SOAP 1.1, namespace {@value #NS}) for the
 * SOAP billers ({@link Biller.Protocol#SOAP}):
 *
 * <ul>
 *   <li>{@code FetchBill {billerCode, billNumber}} -> {@code FetchBillResponse {billerCode, billNumber,
 *       amountDue, dueDate, billPeriod}};
 *   <li>{@code PayBill {billerCode, billNumber, amount, reference}} -> {@code PayBillResponse {status
 *       SUCCESS|PENDING, paymentRef, code}}.
 * </ul>
 *
 * Both delegate to {@link BillAggregatorService} with {@link Protocol#SOAP}: the same bills, magic
 * values, scenarios (partner {@code billagg}), timeout hold, callbacks and payment store (the reference
 * is the payment's {@code transactionId}, so {@code GET /billagg/v1/payments/{reference}} finds it).
 * Every request carries a WS-Security 1.0 UsernameToken (PasswordText, user {@value #USERNAME}) whose
 * password is the property {@value #PASSWORD_PROPERTY}; a missing or wrong one is fault {@code 1002}.
 * Refusals are faults (HTTP 500) with {@code <detail><b:error><b:code/><b:message/></b:error>}: {@code
 * soap:Client} for the aggregator's codes (3001-3004, 9876), unreadable requests and unknown operations
 * (1000) and authentication (1002); {@code soap:Server} for an outage (5030) and the 500-after-send mode
 * (5000).
 */
@Service
public class BillAggregatorSoapService {

  public static final String NS = "urn:billagg:v1";
  public static final String WSSE =
      "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd";
  public static final String USERNAME = "bits";
  public static final String PASSWORD_PROPERTY = "sim.billagg.ws-password";
  static final String PASSWORD_TEXT = "#PasswordText";

  private final BillAggregatorService aggregator;
  private final Validator validator;
  private final Environment environment;

  public BillAggregatorSoapService(BillAggregatorService aggregator, Validator validator, Environment environment) {
    this.aggregator = aggregator;
    this.validator = validator;
    this.environment = environment;
  }

  public SoapReply handle(String request) {
    Document doc;
    try {
      doc = SoapXml.parse(request);
    } catch (IllegalArgumentException e) {
      return fault("Client", "1000", "unreadable request");
    }
    if (!authenticated(SoapXml.header(doc))) {
      return fault("Client", "1002", "authentication failed");
    }
    Element op = SoapXml.bodyElement(doc);
    if (op == null || !NS.equals(op.getNamespaceURI())) {
      return fault("Client", "1000", "unknown operation");
    }
    return switch (op.getLocalName()) {
      case "FetchBill" -> fetch(new BillFetchRequest(text(op, "billerCode"), text(op, "billNumber")));
      case "PayBill" -> pay(new BillPaymentRequest(
          text(op, "reference"), text(op, "billerCode"), text(op, "billNumber"), text(op, "amount")));
      default -> fault("Client", "1000", "unknown operation");
    };
  }

  private SoapReply fetch(BillFetchRequest req) {
    if (!validator.validate(req).isEmpty()) {
      return fault("Client", "1000", "invalid request");
    }
    if (!soapBiller(req.billerCode())) {
      return fault("Client", BillAggregatorMapper.UNKNOWN_BILLER, "Unknown biller");
    }
    SimReply reply = aggregator.fetch(req, Protocol.SOAP);
    if (reply.body() instanceof String) {
      // mock control MALFORMED_RESPONSE: the answer is cut short
      return new SoapReply(200, "<soap:Envelope xmlns:soap=\"" + SoapXml.ENVELOPE + "\"><soap:Body><b:FetchBillRes");
    }
    if (reply.httpStatus() == 200 && reply.body() instanceof BillResponse bill) {
      return SoapXml.ok("<b:FetchBillResponse xmlns:b=\"" + NS + "\">" + el("billerCode", bill.billerCode())
          + el("billNumber", bill.billNumber()) + el("amountDue", bill.amount()) + el("dueDate", bill.dueDate())
          + el("billPeriod", bill.billPeriod()) + "</b:FetchBillResponse>");
    }
    if (reply.body() instanceof BillResponse bill && reply.httpStatus() == 422) {
      return fault("Client", bill.code(), bill.message());
    }
    return serverFault(reply);
  }

  private SoapReply pay(BillPaymentRequest req) {
    if (!validator.validate(req).isEmpty()) {
      return fault("Client", "1000", "invalid request");
    }
    if (!soapBiller(req.billerCode())) {
      return fault("Client", BillAggregatorMapper.UNKNOWN_BILLER, "Unknown biller");
    }
    SimReply reply = aggregator.pay(req, Protocol.SOAP);
    if (reply.body() instanceof String) {
      // the MALFORMED mode: the payment is applied, the answer is cut short
      return new SoapReply(200, "<soap:Envelope xmlns:soap=\"" + SoapXml.ENVELOPE + "\"><soap:Body><b:PayBillRes");
    }
    if (reply.body() instanceof BillPaymentResponse p && (reply.httpStatus() == 200 || reply.httpStatus() == 202)) {
      return SoapXml.ok("<b:PayBillResponse xmlns:b=\"" + NS + "\">"
          + el("status", reply.httpStatus() == 202 ? "PENDING" : p.status()) + el("paymentRef", p.paymentRef())
          + el("code", p.code()) + "</b:PayBillResponse>");
    }
    if (reply.body() instanceof BillPaymentResponse p && reply.httpStatus() == 422) {
      return fault("Client", p.code(), p.message());
    }
    return serverFault(reply);
  }

  private static SoapReply serverFault(SimReply reply) {
    return reply.httpStatus() == 503
        ? fault("Server", "5030", "Service unavailable")
        : fault("Server", "5000", "Internal error");
  }

  private static boolean soapBiller(String code) {
    return BillAggregatorService.BILLERS.stream().anyMatch(b -> b.code().equals(code) && b.protocol() == Protocol.SOAP);
  }

  /** The WS-Security UsernameToken (PasswordText) as the adapters' mw-soap-call sends it. */
  private boolean authenticated(Element header) {
    String expected = environment.getProperty(PASSWORD_PROPERTY);
    if (expected == null || expected.isEmpty()) {
      return false; // fail closed when the simulator has no password
    }
    Element security = header == null ? null : first(header, "Security");
    Element token = security == null ? null : first(security, "UsernameToken");
    if (token == null) {
      return false;
    }
    Element user = first(token, "Username");
    Element pass = first(token, "Password");
    if (user == null || pass == null) {
      return false;
    }
    String type = pass.getAttribute("Type");
    if (!type.isEmpty() && !type.endsWith(PASSWORD_TEXT)) {
      return false;
    }
    return USERNAME.equals(user.getTextContent().trim())
        && MessageDigest.isEqual(
            pass.getTextContent().trim().getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
  }

  private static Element first(Element parent, String name) {
    var nodes = parent.getElementsByTagNameNS(WSSE, name);
    return nodes.getLength() == 0 ? null : (Element) nodes.item(0);
  }

  private static String text(Element op, String name) {
    return SoapXml.text(op, NS, name);
  }

  private static String el(String name, String value) {
    return value == null ? "" : "<b:" + name + ">" + escape(value) + "</b:" + name + ">";
  }

  private static SoapReply fault(String faultCode, String code, String message) {
    return SoapXml.fault(faultCode, message, "<b:error xmlns:b=\"" + NS + "\"><b:code>" + escape(code)
        + "</b:code><b:message>" + escape(message) + "</b:message></b:error>");
  }
}
