package mfs.mw.adapter.atm.partner.kamelet;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
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
import mfs.mw.adapter.atm.partner.PartnerResponse.UnknownReason;
import mfs.mw.adapter.atm.partner.mapping.JsonXmlMapping;
import mfs.mw.adapter.atm.partner.mapping.SoapMapping;
import mfs.mw.adapter.atm.partner.route.PartnerExchange;
import mfs.mw.adapter.atm.partner.route.PartnerResult;
import mfs.mw.adapter.atm.partner.route.PartnerRuntime;
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
 * build the SOAP body with the record's request stylesheet (or, with {@code request=json}, the
 * JSON/XML convention of {@link JsonXmlMapping}), add the WS-Security UsernameToken, and turn the
 * answer or a SOAP fault into the JSON {@code mw-classify} maps with the response stylesheet (or
 * {@code response=json}). HTTP Basic ({@code auth=basic}) is the {@code mw-auth-basic} Kamelet of
 * {@code mw-http-call}, mTLS ({@code sslContextRef}) its named client context ({@code
 * PartnerTlsContexts}); the steps shared with {@code mw-http-call} are {@link MwHttpStep#start} and
 * {@link MwHttpStep#nextAttempt}. A fault is the partner's definite answer (it maps through the
 * record's {@code errorMap}); a transport error or the time limit is the fallback's UNKNOWN
 * (NOT_SENT when the connect failed). Nothing of the request is logged; the audit sees the request
 * JSON and the answer JSON, never the XML.
 */
@Component("mwSoap")
public class MwSoapStep {

  public static final String WSSE = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd";
  public static final String WSU = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd";
  static final String TOKEN_PROFILE = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0";

  /** The SOAP body element the request stylesheet made (cloned for every attempt). */
  static final String ELEMENT = "mwSoapElement";

  // the Kamelet's parameters, as it sets them (properties of the exchange)
  /** {@code address}: the SOAP endpoint. */
  static final String ADDRESS = "mwSoapAddress";
  /** {@code request}: a stylesheet name, or {@value JsonXmlMapping#NAME}. */
  static final String REQUEST = "mwSoapRequest";
  /** {@code response}: a stylesheet name, or {@value JsonXmlMapping#NAME}. */
  static final String RESPONSE = "mwSoapResponse";
  /** {@code namespace}: the body elements' namespace of {@code request=json}. */
  static final String NAMESPACE = "mwSoapNamespace";
  static final String ACTION = "mwSoapAction";
  static final String USERNAME = "mwSoapUsername";
  static final String PASSWORD_SECRET = "mwSoapPasswordSecret";
  static final String PASSWORD_TYPE = "mwSoapPasswordType";
  static final String CONNECT_TIMEOUT = "mwSoapConnectTimeout";
  static final String READ_TIMEOUT = "mwSoapReadTimeout";
  /** {@code sslContextRef}: the named mTLS client context; empty for none. */
  static final String TLS_REF = "mwSoapTlsRef";
  /** True when the call goes out with the named client context (the Kamelet's {@code toD} branches). */
  static final String TLS = "mwSoapTls";

  static final String SOAP_ACTION_HEADER = "SOAPAction";
  /** The camel-cxf request context header: per-call CXF settings (timeouts). */
  static final String REQUEST_CONTEXT = "RequestContext";
  /** Headers that would change where or how CXF sends; only these steps set them. */
  static final List<String> CONTROL_HEADERS = List.of(
      REQUEST_CONTEXT, "ResponseContext", SOAP_ACTION_HEADER, "operationName", "operationNamespace",
      "CamelDestinationOverrideUrl", Exchange.HTTP_URI, Exchange.HTTP_PATH, Exchange.HTTP_QUERY,
      "org.apache.cxf.headers.Header.list");

  private static final Logger log = LoggerFactory.getLogger(MwSoapStep.class);
  private static final SecureRandom RANDOM = new SecureRandom();

  private final JsonMapper json;
  private final Clock clock;

  public MwSoapStep(JsonMapper json, Clock clock) {
    this.json = json;
    this.clock = clock;
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
    Element element = mapping(exchange, REQUEST).requestElement(request);
    exchange.setProperty(ELEMENT, element);
    if (request == null) {
      exchange.setProperty(PartnerExchange.REQUEST_BODY, "{}");
    }
    String address = exchange.getProperty(ADDRESS, String.class);
    exchange.setProperty(PartnerExchange.HTTP_METHOD, "POST");
    exchange.setProperty(PartnerExchange.HTTP_PATH_AND_QUERY, URI.create(address).getRawPath());
    String tls = exchange.getProperty(TLS_REF, "", String.class);
    exchange.setProperty(TLS, tls != null && !tls.isEmpty());
    MwHttpStep.start(exchange, runtime);
  }

  /** Before each attempt: a fresh SOAP payload (and UsernameToken), nothing decided yet. */
  public void beforeAttempt(Exchange exchange) throws ParserConfigurationException {
    MwHttpStep.nextAttempt(exchange);
    var message = exchange.getMessage();
    CONTROL_HEADERS.forEach(message::removeHeader);
    Element element = (Element) exchange.getProperty(ELEMENT, Element.class).cloneNode(true);
    List<Source> body = new ArrayList<>(List.of(new DOMSource(element)));
    List<SoapHeader> headers = new ArrayList<>();
    String user = exchange.getProperty(USERNAME, "", String.class);
    if (!user.isEmpty()) {
      String password = PartnerExchange.runtime(exchange).secret(exchange.getProperty(PASSWORD_SECRET, String.class));
      headers.add(new SoapHeader(new QName(WSSE, "Security"),
          usernameToken(user, password, exchange.getProperty(PASSWORD_TYPE, "PasswordText", String.class), clock.instant())));
    }
    message.setBody(new CxfPayload<>(headers, body, Map.of()));
    String action = exchange.getProperty(ACTION, "", String.class);
    if (!action.isEmpty()) {
      message.setHeader(SOAP_ACTION_HEADER, action);
    }
    Map<String, Object> context = new HashMap<>();
    context.put(org.apache.cxf.message.Message.CONNECTION_TIMEOUT,
        Long.parseLong(exchange.getProperty(CONNECT_TIMEOUT, "3000", String.class)));
    context.put(org.apache.cxf.message.Message.RECEIVE_TIMEOUT,
        Long.parseLong(exchange.getProperty(READ_TIMEOUT, "32000", String.class)));
    message.setHeader(REQUEST_CONTEXT, context);
    exchange.setProperty(PartnerExchange.SENT, true);
  }

  /** The SOAP body of the answer through the response stylesheet; unreadable: UNKNOWN. */
  public void response(Exchange exchange) {
    var message = exchange.getMessage();
    String xml = message.getBody(String.class);
    CONTROL_HEADERS.forEach(message::removeHeader);
    answer(exchange, () -> mapping(exchange, RESPONSE).responseJson(xml));
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
    answer(exchange, () -> mapping(exchange, RESPONSE).faultJson(code, subcode, fault.getReason(), fault.getDetail()));
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

  /** The mapping the Kamelet parameter {@code property} names: {@value JsonXmlMapping#NAME} or a stylesheet. */
  private SoapMapping mapping(Exchange exchange, String property) {
    String name = exchange.getProperty(property, String.class);
    if (JsonXmlMapping.NAME.equals(name)) {
      return new JsonXmlMapping(exchange.getProperty(NAMESPACE, String.class), json);
    }
    return PartnerExchange.runtime(exchange).stylesheet(name);
  }

  /**
   * A WS-Security 1.0 {@code wsse:Security} header with a UsernameToken (OASIS UsernameToken
   * Profile 1.0): {@code PasswordText} sends the password, {@code PasswordDigest} Base64(SHA-1(nonce
   * + created + password)) with the nonce and created time.
   */
  static Element usernameToken(String user, String password, String type, Instant now) throws ParserConfigurationException {
    Document doc = mfs.mw.adapter.atm.partner.mapping.StylesheetPolicy.secureDocument();
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
