package mfs.mw.simulators.controller;

import static mfs.mw.simulators.util.SoapXml.escape;

import java.util.LinkedHashMap;
import java.util.Map;
import mfs.mw.simulators.dto.AggregatorProfile;
import mfs.mw.simulators.dto.SimReply;
import mfs.mw.simulators.service.BillAggregatorMockService;
import mfs.mw.simulators.util.SoapXml;
import mfs.mw.simulators.util.SoapXml.SoapReply;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * The bill aggregator mocks of ticket 114 (one controller, four profiles; the mock party is the first
 * path segment, ADR D33): REST {@code /<profile>/v1/bills/inquiry}, {@code /payments}, {@code
 * /payments/{reference}}, {@code /payments/{reference}/reverse} for sslcommerz, ekpay, agg4 (in their field
 * names, agg4 inside {@code {"req"}} / {@code {"res"}}), {@code POST /ekpay/v1/oauth/token}, SOAP 1.1
 * {@code POST /agg3/v1/soap} (namespace {@code urn:agg3:v1}: GetBill, PayBill, PaymentStatus,
 * ReversePayment). Control: {@code /mock-control/<profile>/...} (scenarios, switch, settings, journal,
 * reset) like every party; test helper {@code GET /sim/agg/{profile}/bills/{biller}/{billNumber}}.
 * Auth (presence of the profile's scheme, values not checked except ekpay's issued token): sslcommerz
 * {@code X-Key-Id} + {@code X-Signature} + {@code X-Timestamp}, ekpay a Bearer token from its token
 * endpoint, agg3 a WS-Security UsernameToken, agg4 {@code Authorization: Basic}, samplebiller (ticket 136, the
 * sample connector's biller, plain camelCase) {@code X-Api-Key}; else 401 / fault 1002.
 */
@RestController
public class BillAggregatorMockController {

  private static final String REST = "{profile:sslcommerz|ekpay|agg4|samplebiller}";
  private static final String WSSE =
      "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd";
  private static final MediaType TEXT_XML_UTF8 = MediaType.parseMediaType("text/xml;charset=UTF-8");

  private final BillAggregatorMockService service;

  public BillAggregatorMockController(BillAggregatorMockService service) {
    this.service = service;
  }

  @PostMapping("/ekpay/v1/oauth/token")
  ResponseEntity<Object> token(@RequestHeader HttpHeaders headers) {
    String a = headers.getFirst(HttpHeaders.AUTHORIZATION);
    if (a == null || !a.regionMatches(true, 0, "Basic ", 0, 6)) {
      return ResponseEntity.status(401).contentType(MediaType.APPLICATION_JSON).body(Map.of("error", "invalid_client"));
    }
    return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(service.token());
  }

  @PostMapping("/" + REST + "/v1/bills/inquiry")
  ResponseEntity<Object> inquiry(
      @PathVariable String profile, @RequestHeader HttpHeaders headers, @RequestBody Map<String, Object> body) {
    AggregatorProfile p = BillAggregatorMockService.profile(profile);
    Map<String, Object> in = unwrap(p, body);
    return respond(p, authed(p, headers) ? service.fetch(p, text(in, p.field("billerCode")), text(in, p.field("billNumber")))
        : unauthenticated());
  }

  @PostMapping("/" + REST + "/v1/payments")
  ResponseEntity<Object> pay(
      @PathVariable String profile, @RequestHeader HttpHeaders headers, @RequestBody Map<String, Object> body) {
    AggregatorProfile p = BillAggregatorMockService.profile(profile);
    Map<String, Object> in = unwrap(p, body);
    return respond(p, authed(p, headers)
        ? service.pay(p, text(in, p.field("reference")), text(in, p.field("billerCode")), text(in, p.field("billNumber")),
            text(in, p.field("amount")))
        : unauthenticated());
  }

  @GetMapping("/" + REST + "/v1/payments/{reference}")
  ResponseEntity<Object> status(
      @PathVariable String profile, @PathVariable String reference, @RequestHeader HttpHeaders headers) {
    AggregatorProfile p = BillAggregatorMockService.profile(profile);
    return respond(p, authed(p, headers) ? service.status(p, reference) : unauthenticated());
  }

  @PostMapping("/" + REST + "/v1/payments/{reference}/reverse")
  ResponseEntity<Object> reverse(
      @PathVariable String profile, @PathVariable String reference, @RequestHeader HttpHeaders headers,
      @RequestBody(required = false) Map<String, Object> body) {
    AggregatorProfile p = BillAggregatorMockService.profile(profile);
    return respond(p, authed(p, headers) ? service.reverse(p, reference) : unauthenticated());
  }

  @GetMapping("/samplebiller/v1/health")
  ResponseEntity<Object> health(@RequestHeader HttpHeaders headers) {
    AggregatorProfile p = BillAggregatorMockService.profile("samplebiller");
    return respond(p, authed(p, headers) ? service.health(p) : unauthenticated());
  }

  @PostMapping(value = "/agg3/v1/soap", consumes = {MediaType.TEXT_XML_VALUE, "application/soap+xml"})
  ResponseEntity<String> soap(@RequestBody(required = false) String envelope) {
    SoapReply reply = soapReply(BillAggregatorMockService.profile("agg3"), envelope);
    return ResponseEntity.status(reply.status()).contentType(TEXT_XML_UTF8).body(reply.envelope());
  }

  @GetMapping("/sim/agg/{profile}/bills/{billerCode}/{billNumber}")
  Map<String, Object> bill(@PathVariable String profile, @PathVariable String billerCode, @PathVariable String billNumber) {
    return service.bill(profile, billerCode, billNumber);
  }

  private SoapReply soapReply(AggregatorProfile p, String request) {
    String ns = "urn:" + p.id() + ":v1";
    Document doc;
    try {
      doc = SoapXml.parse(request);
    } catch (IllegalArgumentException e) {
      return fault(ns, "Client", "1000", "unreadable request");
    }
    if (!wsSecurity(SoapXml.header(doc))) {
      return fault(ns, "Client", "1002", "authentication failed");
    }
    Element op = SoapXml.bodyElement(doc);
    if (op == null) {
      return fault(ns, "Client", "1000", "unknown operation");
    }
    String name = op.getLocalName();
    SimReply reply = switch (name) {
      case "GetBill" -> service.fetch(p, soapText(op, "billerCode"), soapText(op, "billNumber"));
      case "PayBill" -> service.pay(p, soapText(op, "transactionId"), soapText(op, "billerCode"),
          soapText(op, "billNumber"), soapText(op, "amount"));
      case "PaymentStatus" -> service.status(p, soapText(op, "transactionId"));
      case "ReversePayment" -> service.reverse(p, soapText(op, "transactionId"));
      default -> null;
    };
    if (reply == null) {
      return fault(ns, "Client", "1000", "unknown operation");
    }
    if (!(reply.body() instanceof Map<?, ?> raw)) {
      return new SoapReply(200, "<soap:Envelope xmlns:soap=\"" + SoapXml.ENVELOPE + "\"><soap:Body><" + name);
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) raw;
    int status = reply.httpStatus();
    if (status == 200 || status == 202 || status == 404 && "PaymentStatus".equals(name)) {
      StringBuilder xml = new StringBuilder("<").append(name).append("Response xmlns=\"").append(ns).append("\">");
      body.forEach((k, v) -> {
        String el = "GetBill".equals(name) && "amount".equals(k) ? "amountDue" : "period".equals(k) ? "billPeriod" : p.field(k);
        xml.append('<').append(el).append('>').append(escape(String.valueOf(v))).append("</").append(el).append('>');
      });
      return SoapXml.ok(xml.append("</").append(name).append("Response>").toString());
    }
    return fault(ns, status >= 500 ? "Server" : "Client", String.valueOf(body.getOrDefault("code", "5000")),
        String.valueOf(body.getOrDefault("message", "error")));
  }

  private boolean authed(AggregatorProfile p, HttpHeaders headers) {
    return switch (p.auth()) {
      case HMAC_KEY_ID -> present(headers.getFirst("X-Key-Id")) && present(headers.getFirst("X-Signature"))
          && present(headers.getFirst("X-Timestamp"));
      case OAUTH2_CC -> {
        String a = headers.getFirst(HttpHeaders.AUTHORIZATION);
        yield a != null && a.regionMatches(true, 0, "Bearer ", 0, 7) && service.tokenKnown(a.substring(7).trim());
      }
      case BASIC -> {
        String a = headers.getFirst(HttpHeaders.AUTHORIZATION);
        yield a != null && a.regionMatches(true, 0, "Basic ", 0, 6) && a.length() > 6;
      }
      case API_KEY -> present(headers.getFirst("X-Api-Key"));
      case WS_SECURITY -> false;
    };
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> unwrap(AggregatorProfile p, Map<String, Object> body) {
    if (p.envelopeRequest() == null) {
      return body;
    }
    return body.get(p.envelopeRequest()) instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
  }

  private static ResponseEntity<Object> respond(AggregatorProfile p, SimReply reply) {
    Object body = reply.body();
    if (body instanceof Map<?, ?> m) {
      @SuppressWarnings("unchecked")
      Map<String, Object> wire = BillAggregatorMockService.wire(p, (Map<String, Object>) m);
      body = p.envelopeReply() == null ? wire : new LinkedHashMap<>(Map.of(p.envelopeReply(), wire));
    }
    return ResponseEntity.status(reply.httpStatus()).contentType(MediaType.APPLICATION_JSON).body(body);
  }

  private static SimReply unauthenticated() {
    return new SimReply(401, Map.of("status", "FAILED", "code", "1002", "message", "authentication failed"));
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

  private static String soapText(Element op, String name) {
    String v = SoapXml.text(op, "*", name);
    return v.isEmpty() ? null : v;
  }

  private static SoapReply fault(String ns, String faultCode, String code, String message) {
    return SoapXml.fault(faultCode, message, "<b:code xmlns:b=\"" + ns + "\">" + escape(code)
        + "</b:code><b:message xmlns:b=\"" + ns + "\">" + escape(message) + "</b:message>");
  }

  private static String text(Map<String, Object> body, String field) {
    Object v = body.get(field);
    return v == null ? null : v.toString();
  }

  private static boolean present(String v) {
    return v != null && !v.isBlank();
  }
}
