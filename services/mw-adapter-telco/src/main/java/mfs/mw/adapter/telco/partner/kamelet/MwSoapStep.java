package mfs.mw.adapter.telco.partner.kamelet;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.xml.namespace.QName;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Source;
import javax.xml.transform.dom.DOMSource;
import mfs.mw.adapter.telco.partner.PartnerResponse.UnknownReason;
import mfs.mw.adapter.telco.partner.mapping.CompiledStylesheet;
import mfs.mw.adapter.telco.partner.route.PartnerExchange;
import mfs.mw.adapter.telco.partner.route.PartnerResult;
import mfs.mw.adapter.telco.partner.route.PartnerRuntime;
import org.apache.camel.Exchange;
import org.apache.camel.component.cxf.common.CxfPayload;
import org.apache.cxf.binding.soap.SoapFault;
import org.apache.cxf.binding.soap.SoapHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Java steps of the {@code mw-soap-call} Kamelet (bean {@code mwSoap}, ticket 85). The Kamelet
 * holds the Camel side (the breaker with time limit and bulkhead, the {@code cxf} endpoint in
 * PAYLOAD mode, the INQUIRY/STATUS_QUERY loop, the fallback of {@code mw-http-call}); these steps
 * build the SOAP body with the record's request stylesheet, add the WS-Security UsernameToken,
 * and turn the answer or a SOAP fault into the JSON {@code mw-classify} maps with the response
 * stylesheet. A fault is the partner's definite answer (it maps through the record's {@code
 * errorMap}); a transport error or the time limit is the fallback's UNKNOWN (NOT_SENT when the
 * connect failed). Nothing of the request is logged; the audit sees the request JSON and the
 * answer JSON, never the XML.
 */
@Component("mwSoap")
public class MwSoapStep {

  public static final String WSSE = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd";
  public static final String WSU = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd";
  static final String TOKEN_PROFILE = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0";

  /** The SOAP body element the request stylesheet made (cloned for every attempt). */
  static final String ELEMENT = "mwSoapElement";
  /** The camel-cxf request context header: per-call CXF settings (timeouts). */
  static final String REQUEST_CONTEXT = "RequestContext";
  /** Headers that would change where or how CXF sends; only these steps set them. */
  static final List<String> CONTROL_HEADERS = List.of(
      REQUEST_CONTEXT, "ResponseContext", "SOAPAction", "operationName", "operationNamespace",
      "CamelDestinationOverrideUrl", Exchange.HTTP_URI, Exchange.HTTP_PATH, Exchange.HTTP_QUERY,
      "org.apache.cxf.headers.Header.list");

  private static final Logger log = LoggerFactory.getLogger(MwSoapStep.class);
  private static final SecureRandom RANDOM = new SecureRandom();

  private final JsonMapper json;

  public MwSoapStep(JsonMapper json) {
    this.json = json;
  }

  /**
   * The SOAP body element of the request JSON ({@code mw-map}'s, else {@code {}}). A stylesheet
   * that fails throws: nothing was sent, the route configuration answers NOT_SENT {@code
   * MISCONFIGURED}.
   */
  public void prepare(Exchange exchange) {
    PartnerRuntime runtime = PartnerExchange.runtime(exchange);
    var message = exchange.getMessage();
    CONTROL_HEADERS.forEach(message::removeHeader);
    String request = exchange.getProperty(PartnerExchange.REQUEST_BODY, String.class);
    Element element = runtime.stylesheet(exchange.getProperty("mwSoapRequest", String.class)).requestElement(request);
    exchange.setProperty(ELEMENT, element);
    if (request == null) {
      exchange.setProperty(PartnerExchange.REQUEST_BODY, "{}");
    }
    String address = exchange.getProperty("mwSoapAddress", String.class);
    exchange.setProperty(PartnerExchange.HTTP_METHOD, "POST");
    exchange.setProperty(PartnerExchange.HTTP_PATH_AND_QUERY, URI.create(address).getRawPath());
    runtime.config().headers().forEach(message::setHeader);
    String correlationId = message.getHeader(PartnerExchange.CORRELATION_ID, String.class);
    if (correlationId != null) {
      message.setHeader(MwHttpStep.CORRELATION_HEADER, correlationId);
    }
    exchange.setProperty(PartnerExchange.ATTEMPTS, 0);
    exchange.setProperty(PartnerExchange.ATTEMPT_AGAIN, true);
  }

  /** Before each attempt: a fresh SOAP payload (and UsernameToken), nothing decided yet. */
  public void beforeAttempt(Exchange exchange) throws ParserConfigurationException {
    exchange.setProperty(PartnerExchange.ATTEMPT_AGAIN, false);
    exchange.setProperty(PartnerExchange.FINAL, false);
    exchange.setProperty(PartnerExchange.ATTEMPTS, exchange.getProperty(PartnerExchange.ATTEMPTS, 0, Integer.class) + 1);
    exchange.removeProperty(PartnerExchange.RESPONSE_STATUS);
    exchange.removeProperty(PartnerExchange.RESPONSE_BODY);
    var message = exchange.getMessage();
    CONTROL_HEADERS.forEach(message::removeHeader);
    Element element = (Element) exchange.getProperty(ELEMENT, Element.class).cloneNode(true);
    List<Source> body = new ArrayList<>(List.of(new DOMSource(element)));
    List<SoapHeader> headers = new ArrayList<>();
    String user = exchange.getProperty("mwSoapUsername", "", String.class);
    if (!user.isEmpty()) {
      String password = PartnerExchange.runtime(exchange).secret(exchange.getProperty("mwSoapPasswordSecret", String.class));
      headers.add(new SoapHeader(new QName(WSSE, "Security"),
          usernameToken(user, password, exchange.getProperty("mwSoapPasswordType", "PasswordText", String.class), Instant.now())));
    }
    message.setBody(new CxfPayload<>(headers, body, Map.of()));
    String action = exchange.getProperty("mwSoapAction", "", String.class);
    if (!action.isEmpty()) {
      message.setHeader("SOAPAction", action);
    }
    Map<String, Object> context = new HashMap<>();
    context.put(org.apache.cxf.message.Message.CONNECTION_TIMEOUT,
        Long.parseLong(exchange.getProperty("mwSoapConnectTimeout", "3000", String.class)));
    context.put(org.apache.cxf.message.Message.RECEIVE_TIMEOUT,
        Long.parseLong(exchange.getProperty("mwSoapReadTimeout", "32000", String.class)));
    message.setHeader(REQUEST_CONTEXT, context);
    exchange.setProperty(PartnerExchange.SENT, true);
  }

  /** The SOAP body of the answer through the response stylesheet; unreadable: UNKNOWN. */
  public void response(Exchange exchange) {
    var message = exchange.getMessage();
    String xml = message.getBody(String.class);
    CONTROL_HEADERS.forEach(message::removeHeader);
    answer(exchange, () -> stylesheet(exchange).responseJson(xml));
  }

  /** A SOAP fault: the partner's definite answer, through the response stylesheet. */
  public void fault(Exchange exchange) {
    Throwable caught = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Throwable.class);
    CONTROL_HEADERS.forEach(exchange.getMessage()::removeHeader);
    if (!(caught instanceof SoapFault fault)) {
      exchange.setProperty(PartnerExchange.FINAL, true);
      exchange.getMessage().setBody(json.writeValueAsString(PartnerResult.unknown(UnknownReason.CONNECTION_RESET)));
      return;
    }
    String code = fault.getFaultCode() == null ? null : fault.getFaultCode().getLocalPart();
    String subcode = fault.getSubCode() == null ? null : fault.getSubCode().getLocalPart();
    answer(exchange, () -> stylesheet(exchange).faultJson(code, subcode, fault.getReason(), fault.getDetail()));
  }

  private void answer(Exchange exchange, java.util.function.Supplier<String> transform) {
    exchange.setProperty(PartnerExchange.RESPONSE_STATUS, 200);
    String body;
    try {
      body = transform.get();
    } catch (IllegalArgumentException e) {
      log.atWarn()
          .addKeyValue("event", "partner.call.malformed")
          .addKeyValue("partnerId", PartnerExchange.runtime(exchange).id())
          .log("partner SOAP answer not understood by its response stylesheet");
      exchange.setProperty(PartnerExchange.RESPONSE_BODY, "");
      exchange.setProperty(PartnerExchange.FINAL, true);
      exchange.getMessage().setBody(json.writeValueAsString(PartnerResult.unknown(UnknownReason.MALFORMED_REPLY)));
      return;
    }
    exchange.setProperty(PartnerExchange.RESPONSE_BODY, body);
    exchange.getMessage().setBody(body);
  }

  private static CompiledStylesheet stylesheet(Exchange exchange) {
    return PartnerExchange.runtime(exchange).stylesheet(exchange.getProperty("mwSoapResponse", String.class));
  }

  /**
   * A WS-Security 1.0 {@code wsse:Security} header with a UsernameToken (OASIS UsernameToken
   * Profile 1.0): {@code PasswordText} sends the password, {@code PasswordDigest} Base64(SHA-1(nonce
   * + created + password)) with the nonce and created time.
   */
  static Element usernameToken(String user, String password, String type, Instant now) throws ParserConfigurationException {
    Document doc = mfs.mw.adapter.telco.partner.mapping.StylesheetPolicy.secureDocument();
    Element security = doc.createElementNS(WSSE, "wsse:Security");
    security.setAttributeNS("http://www.w3.org/2000/xmlns/", "xmlns:wsu", WSU);
    doc.appendChild(security);
    Element token = doc.createElementNS(WSSE, "wsse:UsernameToken");
    security.appendChild(token);
    Element name = doc.createElementNS(WSSE, "wsse:Username");
    name.setTextContent(user);
    token.appendChild(name);
    Element pass = doc.createElementNS(WSSE, "wsse:Password");
    token.appendChild(pass);
    if ("PasswordDigest".equals(type)) {
      byte[] nonce = new byte[16];
      RANDOM.nextBytes(nonce);
      String created = now.truncatedTo(ChronoUnit.SECONDS).toString();
      pass.setAttribute("Type", TOKEN_PROFILE + "#PasswordDigest");
      pass.setTextContent(digest(nonce, created, password));
      Element n = doc.createElementNS(WSSE, "wsse:Nonce");
      n.setAttribute("EncodingType", "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary");
      n.setTextContent(Base64.getEncoder().encodeToString(nonce));
      token.appendChild(n);
      Element c = doc.createElementNS(WSU, "wsu:Created");
      c.setTextContent(created);
      token.appendChild(c);
    } else {
      pass.setAttribute("Type", TOKEN_PROFILE + "#PasswordText");
      pass.setTextContent(password);
    }
    return security;
  }

  /** Base64(SHA-1(nonce + created + password)), the UsernameToken Profile digest. */
  static String digest(byte[] nonce, String created, String password) {
    try {
      MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
      sha1.update(nonce);
      sha1.update(created.getBytes(StandardCharsets.UTF_8));
      sha1.update(password.getBytes(StandardCharsets.UTF_8));
      return Base64.getEncoder().encodeToString(sha1.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
