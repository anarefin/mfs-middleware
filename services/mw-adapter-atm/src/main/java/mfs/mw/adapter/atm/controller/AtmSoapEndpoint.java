package mfs.mw.adapter.atm.controller;

import mfs.mw.adapter.atm.exception.SoapFaultResolver;
import mfs.mw.adapter.atm.service.AtmInboundService;
import org.springframework.ws.server.endpoint.annotation.Endpoint;
import org.springframework.ws.server.endpoint.annotation.PayloadRoot;
import org.springframework.ws.server.endpoint.annotation.RequestPayload;
import org.springframework.ws.server.endpoint.annotation.ResponsePayload;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * The UCB ATM switch's inbound SOAP API (ticket 120a, FRS UC-17, EXT-16, PLT-050): mapping only.
 * The payload is schema-validated ({@code atm-soap.v1.xsd}) and the WS-Security UsernameToken
 * checked before this runs; each operation calls {@link AtmInboundService}. The token element of
 * VerifyTokenAndDebit is handed to the service for mw-auth's check, never logged or echoed (FL-11).
 */
@Endpoint
public class AtmSoapEndpoint {

  private final AtmInboundService service;

  public AtmSoapEndpoint(AtmInboundService service) {
    this.service = service;
  }

  @PayloadRoot(namespace = AtmSoap.NAMESPACE, localPart = "VerifyTokenAndDebitRequest")
  @ResponsePayload
  public Element verifyTokenAndDebit(@RequestPayload Element body) {
    return ack("VerifyTokenAndDebitResponse", service.verifyAndDebit(
        text(body, "atmTxnRef"), text(body, "atmId"), text(body, "msisdn"), text(body, "token"),
        Long.parseLong(text(body, "amountMinor"))));
  }

  @PayloadRoot(namespace = AtmSoap.NAMESPACE, localPart = "ConfirmDispenseRequest")
  @ResponsePayload
  public Element confirmDispense(@RequestPayload Element body) {
    return ack("ConfirmDispenseResponse", service.confirmDispense(text(body, "atmTxnRef"), text(body, "atmId")));
  }

  @PayloadRoot(namespace = AtmSoap.NAMESPACE, localPart = "ReversalAdviceRequest")
  @ResponsePayload
  public Element reversalAdvice(@RequestPayload Element body) {
    return ack("ReversalAdviceResponse", service.reversalAdvice(
        text(body, "atmTxnRef"), text(body, "atmId"), text(body, "reasonCode")));
  }

  @PayloadRoot(namespace = AtmSoap.NAMESPACE, localPart = "CashInRequest")
  @ResponsePayload
  public Element cashIn(@RequestPayload Element body) {
    return ack("CashInResponse", service.cashIn(
        text(body, "atmTxnRef"), text(body, "atmId"), text(body, "msisdn"),
        Long.parseLong(text(body, "amountMinor"))));
  }

  private static String text(Element parent, String name) {
    NodeList list = parent.getElementsByTagNameNS(AtmSoap.NAMESPACE, name);
    return list.getLength() == 0 ? null : list.item(0).getTextContent().strip();
  }

  private static Element ack(String name, AtmInboundService.Ack ack) {
    Document doc = SoapFaultResolver.Xml.newDocument();
    Element root = doc.createElementNS(AtmSoap.NAMESPACE, name);
    doc.appendChild(root);
    append(root, "atmTxnRef", ack.atmTxnRef());
    append(root, "status", ack.status());
    append(root, "txnId", ack.txnId());
    return root;
  }

  private static void append(Element parent, String name, String value) {
    Element child = parent.getOwnerDocument().createElementNS(AtmSoap.NAMESPACE, name);
    child.setTextContent(value);
    parent.appendChild(child);
  }
}
