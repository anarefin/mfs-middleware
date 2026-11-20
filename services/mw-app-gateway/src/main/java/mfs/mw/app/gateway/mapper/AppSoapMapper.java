package mfs.mw.app.gateway.mapper;

import java.util.LinkedHashMap;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import mfs.mw.app.gateway.controller.AppSoap;
import mfs.mw.app.gateway.dto.Amount;
import mfs.mw.app.gateway.dto.BalanceRequest;
import mfs.mw.app.gateway.dto.BalanceView;
import mfs.mw.app.gateway.dto.ConfirmRequest;
import mfs.mw.app.gateway.dto.EncryptedPin;
import mfs.mw.app.gateway.dto.ErrorInfo;
import mfs.mw.app.gateway.dto.Party;
import mfs.mw.app.gateway.dto.PreviewRequest;
import mfs.mw.app.gateway.dto.PreviewView;
import mfs.mw.app.gateway.dto.Quote;
import mfs.mw.app.gateway.dto.TransactionRequest;
import mfs.mw.app.gateway.dto.TransactionView;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * XML of the app SOAP facade to the REST DTOs and back (ticket 108). Mapping only: the services
 * decide what a request means. Absent optional elements stay null, as an omitted JSON field does.
 */
@Component
public class AppSoapMapper {

  public String group(Element body) {
    return text(body, "group");
  }

  public PreviewRequest previewRequest(Element body) {
    return new PreviewRequest(
        counterParty(child(body, "creditParty")), bank(child(body, "debitParty")),
        partner(child(body, "partner")), amount(child(body, "amount")), text(body, "locale"),
        attributes(child(body, "attributes")), text(body, "linkedAccountId"));
  }

  public ConfirmRequest confirmRequest(Element body) {
    return new ConfirmRequest(
        text(body, "previewId"), counterParty(child(body, "creditParty")), bank(child(body, "debitParty")),
        partner(child(body, "partner")), amount(child(body, "amount")), pin(child(body, "encryptedPin")),
        text(body, "locale"), bool(body, "confirmDuplicate"), attributes(child(body, "attributes")),
        text(body, "linkedAccountId"));
  }

  public BalanceRequest balanceRequest(Element body) {
    return new BalanceRequest(pin(child(body, "encryptedPin")), text(body, "locale"));
  }

  public String txnId(Element body) {
    return text(body, "txnId");
  }

  public Element preview(PreviewView view) {
    Element root = root("PreviewResponse");
    text(root, "previewId", view.previewId());
    text(root, "expiresAt", view.expiresAt() == null ? null : view.expiresAt().toString());
    text(root, "txnType", view.txnType());
    money(root, "amount", view.amount());
    money(root, "fee", view.fee());
    money(root, "vat", view.vat());
    money(root, "commission", view.commission());
    money(root, "total", view.total());
    party(root, "counterparty", view.counterparty());
    limits(root, view.limits());
    money(root, "outstanding", view.outstanding());
    money(root, "minimum", view.minimum());
    return root;
  }

  public Element transaction(String element, TransactionView view) {
    Element root = root(element);
    text(root, "txnId", view.txnId());
    text(root, "status", view.status());
    text(root, "txnType", view.txnType());
    party(root, "debitParty", view.debitParty());
    party(root, "creditParty", view.creditParty());
    money(root, "amount", view.amount());
    money(root, "fee", view.fee());
    text(root, "coreRef", view.coreRef());
    error(root, view.error());
    text(root, "requestedAt", view.requestedAt() == null ? null : view.requestedAt().toString());
    text(root, "updatedAt", view.updatedAt() == null ? null : view.updatedAt().toString());
    return root;
  }

  public Element balance(BalanceView view) {
    Element root = root("GetBalanceResponse");
    text(root, "txnId", view.txnId());
    money(root, "available", view.available());
    text(root, "asOf", view.asOf() == null ? null : view.asOf().toString());
    return root;
  }

  private static Amount amount(Element element) {
    if (element == null) {
      return null;
    }
    return new Amount(longText(element, "valueMinor"), text(element, "currency"));
  }

  private static TransactionRequest.CounterParty counterParty(Element element) {
    if (element == null) {
      return null;
    }
    return new TransactionRequest.CounterParty(text(element, "type"), text(element, "id"));
  }

  private static TransactionRequest.BankAccount bank(Element element) {
    if (element == null) {
      return null;
    }
    return new TransactionRequest.BankAccount(text(element, "type"), text(element, "id"));
  }

  private static TransactionRequest.Partner partner(Element element) {
    if (element == null) {
      return null;
    }
    return new TransactionRequest.Partner(text(element, "partnerId"), text(element, "productCode"),
        text(element, "billerCode"));
  }

  private static EncryptedPin pin(Element element) {
    if (element == null) {
      return null;
    }
    return new EncryptedPin(text(element, "keyId"), text(element, "ciphertext"));
  }

  private static Map<String, String> attributes(Element element) {
    if (element == null) {
      return null;
    }
    Map<String, String> map = new LinkedHashMap<>();
    for (Element entry : children(element, "entry")) {
      String key = text(entry, "key");
      String value = text(entry, "value");
      if (key != null) {
        map.put(key, value);
      }
    }
    return map.isEmpty() ? null : map;
  }

  /** XSD boolean is {@code true}, {@code false}, {@code 1} or {@code 0}. {@code Boolean.valueOf} rejects {@code 1}. */
  private static Boolean bool(Element body, String name) {
    String value = text(body, name);
    if (value == null) {
      return null;
    }
    if ("1".equals(value)) {
      return Boolean.TRUE;
    }
    if ("0".equals(value)) {
      return Boolean.FALSE;
    }
    return Boolean.valueOf(value);
  }

  private static Long longText(Element element, String name) {
    String value = text(element, name);
    if (value == null) {
      return null;
    }
    return Long.valueOf(value);
  }

  private static void limits(Element parent, PreviewView.Limits limits) {
    if (limits == null) {
      return;
    }
    Element el = element(parent, "limits");
    if (limits.remaining() == null) {
      return;
    }
    Quote.Remaining remaining = limits.remaining();
    Element rem = element(el, "remaining");
    money(rem, "perTxn", remaining.perTxn());
    money(rem, "daily", remaining.daily());
    money(rem, "monthly", remaining.monthly());
    if (remaining.dailyCount() != null) {
      text(rem, "dailyCount", remaining.dailyCount().toString());
    }
    if (remaining.monthlyCount() != null) {
      text(rem, "monthlyCount", remaining.monthlyCount().toString());
    }
  }

  private static void error(Element parent, ErrorInfo error) {
    if (error == null) {
      return;
    }
    Element el = element(parent, "error");
    text(el, "code", error.code());
    text(el, "message", error.message());
    text(el, "messageBn", error.messageBn());
    text(el, "retryable", Boolean.toString(error.retryable()));
  }

  private static void party(Element parent, String name, Party party) {
    if (party == null) {
      return;
    }
    Element el = element(parent, name);
    text(el, "type", party.type());
    text(el, "id", party.id());
    text(el, "name", party.name());
    text(el, "walletType", party.walletType());
  }

  private static void money(Element parent, String name, Quote.Money money) {
    if (money == null) {
      return;
    }
    Element el = element(parent, name);
    text(el, "valueMinor", Long.toString(money.valueMinor()));
    text(el, "currency", money.currency());
  }

  private static void money(Element parent, String name, Amount amount) {
    if (amount == null || amount.valueMinor() == null) {
      return;
    }
    Element el = element(parent, name);
    text(el, "valueMinor", amount.valueMinor().toString());
    text(el, "currency", amount.currency());
  }

  private static Element root(String name) {
    Document doc = document();
    Element root = doc.createElementNS(AppSoap.NAMESPACE, name);
    doc.appendChild(root);
    return root;
  }

  private static Element element(Element parent, String name) {
    Element child = parent.getOwnerDocument().createElementNS(AppSoap.NAMESPACE, name);
    parent.appendChild(child);
    return child;
  }

  private static void text(Element parent, String name, String value) {
    if (value == null) {
      return;
    }
    element(parent, name).setTextContent(value);
  }

  static Element child(Element parent, String name) {
    if (parent == null) {
      return null;
    }
    for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
      if (node instanceof Element element && name.equals(element.getLocalName())) {
        return element;
      }
    }
    return null;
  }

  private static java.util.List<Element> children(Element parent, String name) {
    java.util.List<Element> found = new java.util.ArrayList<>();
    for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
      if (node instanceof Element element && name.equals(element.getLocalName())) {
        found.add(element);
      }
    }
    return found;
  }

  private static String text(Element parent, String name) {
    Element child = child(parent, name);
    if (child == null) {
      return null;
    }
    String value = child.getTextContent();
    if (value == null) {
      return null;
    }
    String stripped = value.strip();
    return stripped.isEmpty() ? null : stripped;
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
