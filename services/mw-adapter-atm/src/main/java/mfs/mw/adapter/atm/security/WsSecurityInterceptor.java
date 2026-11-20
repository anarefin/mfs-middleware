package mfs.mw.adapter.atm.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
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
 * WS-Security UsernameToken check of every inbound ATM call (tickets 120a, 120b1, FRS EXT-16,
 * PLT-008). The SOAP header {@code wsse:Security/wsse:UsernameToken} must carry the switch's user
 * name, a {@code PasswordDigest} password (Base64(SHA-1(nonce + created + password)), OASIS
 * UsernameToken profile 1.0), a {@code wsse:Nonce} and a {@code wsu:Created}. {@code PasswordText}
 * is refused, so the password never crosses the wire. {@code Created} must be within
 * {@value #SKEW_SECONDS} s of now and the nonce is accepted once (table {@code callback_nonce}),
 * so a captured request cannot be replayed. Anything else is the SOAP fault {@code MW-AUTH-001}
 * before the endpoint runs; the reason is logged, never the header or the password. TLS is the
 * edge's (route {@code partner-ucbatm-ws}).
 */
public class WsSecurityInterceptor implements EndpointInterceptor {

  static final long SKEW_SECONDS = 300;
  /** A nonce is remembered while a Created on either side of now can still be accepted. */
  static final Duration NONCE_WINDOW = Duration.ofSeconds(2 * SKEW_SECONDS);
  private static final int MAX_NONCE_CHARS = 128;

  private static final Logger log = LoggerFactory.getLogger(WsSecurityInterceptor.class);

  private final AtmInboundProperties properties;
  private final NonceStore nonces;
  private final Clock clock;

  public WsSecurityInterceptor(AtmInboundProperties properties, NonceStore nonces, Clock clock) {
    this.properties = properties;
    this.nonces = nonces;
    this.clock = clock;
  }

  @Override
  public boolean handleRequest(MessageContext messageContext, Object endpoint) {
    String failure = messageContext.getRequest() instanceof SoapMessage soap
        ? check(soap.getSoapHeader())
        : "not a SOAP message";
    if (failure != null) {
      log.atWarn()
          .addKeyValue("event", "atm.inbound.auth_failed")
          .addKeyValue("errorCode", ErrorCodes.AUTH_SIGNATURE)
          .log("ATM call without a valid UsernameToken: {}", failure);
      throw new MwException(ErrorCodes.AUTH_SIGNATURE, "Authentication failed").withStatus(HttpStatus.UNAUTHORIZED);
    }
    return true;
  }

  /** Null when the header authenticates the switch, else the reason (no secret in it). */
  private String check(SoapHeader header) {
    String expected = properties.wsPassword();
    if (expected == null || expected.isBlank()) {
      return "no password configured";
    }
    Element token = usernameToken(header);
    if (token == null) {
      return "no UsernameToken";
    }
    Element user = child(token, AtmSoap.WSSE_NS, "Username");
    Element password = child(token, AtmSoap.WSSE_NS, "Password");
    Element nonce = child(token, AtmSoap.WSSE_NS, "Nonce");
    Element created = child(token, AtmSoap.WSU_NS, "Created");
    if (user == null || password == null || nonce == null || created == null) {
      return "UsernameToken without Username, Password, Nonce or Created";
    }
    if (!AtmSoap.PASSWORD_DIGEST.equals(password.getAttribute("Type"))) {
      return "password type is not PasswordDigest";
    }
    String nonceText = nonce.getTextContent().strip();
    String createdText = created.getTextContent().strip();
    byte[] nonceBytes;
    Instant createdAt;
    try {
      nonceBytes = Base64.getDecoder().decode(nonceText);
      createdAt = Instant.parse(createdText);
    } catch (IllegalArgumentException | DateTimeParseException e) {
      return "unreadable Nonce or Created";
    }
    if (nonceBytes.length < 8 || nonceText.length() > MAX_NONCE_CHARS) {
      return "nonce too short or too long";
    }
    boolean userOk = equal(properties.wsUsername(), user.getTextContent().strip());
    boolean digestOk = equal(digest(nonceBytes, createdText, expected), password.getTextContent().strip());
    if (!(userOk & digestOk)) {
      return "bad user name or password digest";
    }
    Instant now = clock.instant();
    if (createdAt.isBefore(now.minusSeconds(SKEW_SECONDS)) || createdAt.isAfter(now.plusSeconds(SKEW_SECONDS))) {
      return "Created outside the accepted window";
    }
    if (!nonces.firstUse(properties.partnerId(), nonceText, NONCE_WINDOW)) {
      return "nonce already used";
    }
    return null;
  }

  private static Element usernameToken(SoapHeader header) {
    if (header == null) {
      return null;
    }
    Iterator<SoapHeaderElement> it = header.examineAllHeaderElements();
    while (it.hasNext()) {
      SoapHeaderElement element = it.next();
      if (AtmSoap.WSSE_NS.equals(element.getName().getNamespaceURI())
          && "Security".equals(element.getName().getLocalPart())) {
        return child((Element) toNode(element), AtmSoap.WSSE_NS, "UsernameToken");
      }
    }
    return null;
  }

  /** Base64(SHA-1(nonce + created + password)), the UsernameToken profile's PasswordDigest. */
  static String digest(byte[] nonce, String created, String password) {
    try {
      MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
      sha1.update(nonce);
      sha1.update(created.getBytes(StandardCharsets.UTF_8));
      sha1.update(password.getBytes(StandardCharsets.UTF_8));
      return Base64.getEncoder().encodeToString(sha1.digest());
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("SHA-1 unavailable", e);
    }
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

  private static Element child(Element parent, String namespace, String localName) {
    if (parent == null) {
      return null;
    }
    NodeList children = parent.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      if (children.item(i) instanceof Element e
          && namespace.equals(e.getNamespaceURI())
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
