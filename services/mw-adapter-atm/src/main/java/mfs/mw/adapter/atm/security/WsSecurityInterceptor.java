package mfs.mw.adapter.atm.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Iterator;
import mfs.mw.adapter.atm.config.AtmInboundProperties;
import mfs.mw.adapter.atm.controller.AtmSoap;
import mfs.mw.adapter.atm.exception.ErrorCodes;
import mfs.mw.adapter.atm.exception.MwException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.ws.context.MessageContext;
import org.springframework.ws.server.EndpointInterceptor;
import org.springframework.ws.soap.SoapHeader;
import org.springframework.ws.soap.SoapHeaderElement;
import org.springframework.ws.soap.SoapMessage;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * WS-Security UsernameToken check of every inbound ATM call (ticket 120a, FRS EXT-16): the SOAP
 * header {@code wsse:Security/wsse:UsernameToken} must carry the switch's user name and its
 * {@code PasswordText} password, compared in constant time. Anything else is a SOAP fault
 * {@code MW-AUTH-001} before the endpoint runs. TLS (and mTLS) is the edge's job (ticket 100).
 * Neither the header nor the password is ever logged.
 */
public class WsSecurityInterceptor implements EndpointInterceptor {

  private static final Logger log = LoggerFactory.getLogger(WsSecurityInterceptor.class);

  private final AtmInboundProperties properties;

  public WsSecurityInterceptor(AtmInboundProperties properties) {
    this.properties = properties;
  }

  @Override
  public boolean handleRequest(MessageContext messageContext, Object endpoint) {
    if (!(messageContext.getRequest() instanceof SoapMessage soap) || !authenticated(soap.getSoapHeader())) {
      log.atWarn().addKeyValue("event", "atm.inbound.auth_failed").log("ATM call without a valid UsernameToken");
      throw new MwException(ErrorCodes.AUTH_SIGNATURE, "Authentication failed").withStatus(HttpStatus.UNAUTHORIZED);
    }
    return true;
  }

  private boolean authenticated(SoapHeader header) {
    String expected = properties.wsPassword();
    if (header == null || expected == null || expected.isBlank()) {
      return false;
    }
    Iterator<SoapHeaderElement> it = header.examineAllHeaderElements();
    while (it.hasNext()) {
      SoapHeaderElement element = it.next();
      if (!AtmSoap.WSSE_NS.equals(element.getName().getNamespaceURI())
          || !"Security".equals(element.getName().getLocalPart())) {
        continue;
      }
      Element security = (Element) toNode(element);
      Element token = child(security, "UsernameToken");
      if (token == null) {
        return false;
      }
      Element user = child(token, "Username");
      Element password = child(token, "Password");
      if (user == null || password == null) {
        return false;
      }
      String type = password.getAttribute("Type");
      if (!type.isEmpty() && !AtmSoap.PASSWORD_TEXT.equals(type)) {
        return false;
      }
      boolean userOk = equal(properties.wsUsername(), user.getTextContent().strip());
      boolean passwordOk = equal(expected, password.getTextContent());
      return userOk & passwordOk;
    }
    return false;
  }

  private static Node toNode(SoapHeaderElement element) {
    javax.xml.transform.dom.DOMResult result = new javax.xml.transform.dom.DOMResult();
    try {
      javax.xml.transform.TransformerFactory factory = javax.xml.transform.TransformerFactory.newInstance();
      factory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.newTransformer().transform(element.getSource(), result);
    } catch (javax.xml.transform.TransformerException e) {
      throw new IllegalStateException("WS-Security header could not be read", e);
    }
    Node node = result.getNode();
    return node.getNodeType() == Node.DOCUMENT_NODE ? ((org.w3c.dom.Document) node).getDocumentElement() : node;
  }

  private static Element child(Element parent, String localName) {
    NodeList children = parent.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      if (children.item(i) instanceof Element e
          && AtmSoap.WSSE_NS.equals(e.getNamespaceURI())
          && localName.equals(e.getLocalName())) {
        return e;
      }
    }
    return null;
  }

  private static boolean equal(String expected, String actual) {
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
  }

  @Override
  public boolean handleResponse(MessageContext messageContext, Object endpoint) {
    return true;
  }

  @Override
  public boolean handleFault(MessageContext messageContext, Object endpoint) {
    return true;
  }

  @Override
  public void afterCompletion(MessageContext messageContext, Object endpoint, Exception ex) {
    // nothing to release
  }
}
