package mfs.mw.simulators.service;

import static mfs.mw.simulators.util.SoapXml.escape;

import java.util.Map;
import java.util.function.UnaryOperator;
import mfs.mw.simulators.dto.BankProfile;
import mfs.mw.simulators.dto.SimReply;
import mfs.mw.simulators.util.SoapXml;
import mfs.mw.simulators.util.SoapXml.SoapReply;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * The SOAP 1.1 front end of the simulated bank gateway (ticket 117d): {@code POST /bank/<code>/v1/soap},
 * namespace {@code urn:bank:<code>:v1}, the body element decides: {@code ValidateAccount {accountNumber}},
 * {@code Transfer {transactionId, accountNumber, amount, direction}}, {@code TransferStatus
 * {transactionId}}, {@code ReverseTransfer {transactionId, reversalId, amount}}, answered {@code
 * <Op>Response} with the REST fields as child elements (a status inquiry of an unknown transfer answers
 * {@code status NOT_FOUND}). Refusals are {@code soap:Client} faults with {@code <detail><b:code/>
 * <b:message/></detail>} (HTTP 500); MAINTENANCE {@code soap:Server} 5030, the 500-after-send mode 5000;
 * {@code 1000} unreadable or unknown, {@code 1002} the profile's auth missing (basic: {@code Authorization:
 * Basic}; ws-security: a WS-Security UsernameToken with a Username and a Password). Same state, triggers
 * and mock party as the REST banks ({@link BankGatewayService}).
 */
@Service
public class BankGatewaySoapService {

  private static final String WSSE =
      "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd";

  private final BankGatewayService gateway;

  public BankGatewaySoapService(BankGatewayService gateway) {
    this.gateway = gateway;
  }

  public SoapReply handle(BankProfile p, UnaryOperator<String> header, String request) {
    String ns = "urn:bank:" + p.code() + ":v1";
    Document doc;
    try {
      doc = SoapXml.parse(request);
    } catch (IllegalArgumentException e) {
      return fault(ns, "Client", "1000", "unreadable request");
    }
    boolean authed = "ws-security".equals(p.auth())
        ? wsSecurity(SoapXml.header(doc))
        : BankGatewayService.httpAuthPresent(p, header);
    if (!authed) {
      return fault(ns, "Client", "1002", "authentication failed");
    }
    Element op = SoapXml.bodyElement(doc);
    if (op == null) {
      return fault(ns, "Client", "1000", "unknown operation");
    }
    String name = op.getLocalName();
    SimReply reply = switch (name) {
      case "ValidateAccount" -> gateway.verify(p, text(op, "accountNumber"));
      case "Transfer" -> gateway.transfer(p, text(op, "transactionId"), text(op, "accountNumber"),
          text(op, "amount"), text(op, "direction"));
      case "TransferStatus" -> gateway.status(p, text(op, "transactionId"));
      case "ReverseTransfer" -> gateway.reverse(p, text(op, "transactionId"), text(op, "reversalId"));
      default -> null;
    };
    return reply == null ? fault(ns, "Client", "1000", "unknown operation") : render(ns, name, reply);
  }

  @SuppressWarnings("unchecked")
  private static SoapReply render(String ns, String op, SimReply reply) {
    if (!(reply.body() instanceof Map<?, ?> raw)) {
      return new SoapReply(200, "<soap:Envelope xmlns:soap=\"" + SoapXml.ENVELOPE + "\"><soap:Body><" + op);
    }
    Map<String, Object> body = (Map<String, Object>) raw;
    int status = reply.httpStatus();
    boolean notFoundStatus = status == 404 && "TransferStatus".equals(op);
    if (status == 200 || status == 202 || notFoundStatus) {
      StringBuilder xml = new StringBuilder("<").append(op).append("Response xmlns=\"").append(ns).append("\">");
      body.forEach((k, v) -> xml.append('<').append(k).append('>').append(escape(String.valueOf(v)))
          .append("</").append(k).append('>'));
      return SoapXml.ok(xml.append("</").append(op).append("Response>").toString());
    }
    String code = String.valueOf(body.getOrDefault("code", "5000"));
    String message = String.valueOf(body.getOrDefault("message", "error"));
    return fault(ns, status >= 500 ? "Server" : "Client", code, message);
  }

  private static boolean wsSecurity(Element header) {
    if (header == null) {
      return false;
    }
    var tokens = header.getElementsByTagNameNS(WSSE, "UsernameToken");
    if (tokens.getLength() == 0) {
      return false;
    }
    Element token = (Element) tokens.item(0);
    return !SoapXml.text(token, WSSE, "Username").isEmpty() && !SoapXml.text(token, WSSE, "Password").isEmpty();
  }

  private static String text(Element op, String name) {
    String v = SoapXml.text(op, "*", name);
    return v.isEmpty() ? null : v;
  }

  private static SoapReply fault(String ns, String faultCode, String code, String message) {
    return SoapXml.fault(faultCode, message, "<b:code xmlns:b=\"" + ns + "\">" + escape(code)
        + "</b:code><b:message xmlns:b=\"" + ns + "\">" + escape(message) + "</b:message>");
  }
}
