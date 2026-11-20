package mfs.mw.simulators.service;

import static mfs.mw.simulators.util.SoapXml.escape;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import mfs.mw.simulators.dto.SimMode;
import mfs.mw.simulators.dto.SimReply;
import mfs.mw.simulators.service.mockcontrol.Decision;
import mfs.mw.simulators.exception.MockAbortException;
import mfs.mw.simulators.service.mockcontrol.MockCalls;
import mfs.mw.simulators.service.mockcontrol.MockControl;
import mfs.mw.simulators.service.mockcontrol.PartyState;
import mfs.mw.simulators.util.SoapXml;
import mfs.mw.simulators.util.SoapXml.SoapReply;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * The merchant system SOAP mock (ticket 115b, FRS UC-11, EXT-11, MCK-01 SOAP): {@code POST
 * /merchant-soap/v1/soap} (SOAP 1.1, namespace {@value #NS}), the other side of mw-adapter-merchant's
 * {@code merchant-soap} partner. Mock party {@value #PARTY} on {@code /mock-control/merchant-soap}
 * (operations validate, notify, query, refund; scope keys merchantId, txnRef). A separate class from the
 * checkout merchant IPN receiver ({@code MerchantIpnController}, party {@code merchant}).
 *
 * <ul>
 *   <li>{@code ValidateMerchant {merchantId, tillId}} -> {@code ValidateMerchantResponse {status ACTIVE,
 *       merchantRef}}; seeded merchants {@link #MERCHANTS}: an unknown one is fault 5001, an inactive one
 *       5002, a till it does not have 5003.
 *   <li>{@code NotifyPayment {merchantId, tillId, amount, reference}} -> {@code NotifyPaymentResponse
 *       {status RECEIVED, notifyRef, code 0000}}; kept per reference (a repeat answers the first).
 *   <li>{@code QueryNotify {merchantId, reference}} -> {@code QueryNotifyResponse {status RECEIVED |
 *       NOT_FOUND, notifyRef}}.
 *   <li>{@code Refund {merchantId, amount, reference, refundId}} -> {@code RefundResponse {status
 *       REFUNDED, refundRef, code 0000}}; stateful: once per refundId (a repeat answers the first), and
 *       refunded amounts never pass the notified one (else 5004).
 * </ul>
 *
 * Every request carries a WS-Security UsernameToken (user {@value #USERNAME}, PasswordText = property
 * {@value #PASSWORD_PROPERTY}, from Vault {@code secret/mw-simulators} synced from {@code
 * MERCHANT_SOAP_WS_PASSWORD}); a missing or wrong one is fault 1002 (fail closed without a password).
 * Mock control: BUSINESS_ERROR is a {@code soap:Client} fault with the scenario's code, HTTP_5XX a
 * {@code soap:Server} fault 5000 after the effect (the adapter reads it UNKNOWN), TIMEOUT holds the
 * answer, MALFORMED cuts it short, DOWN / MAINTENANCE as every party.
 */
@Service
public class MerchantSoapMock implements PartyState {

  private static final Logger log = LoggerFactory.getLogger(MerchantSoapMock.class);

  public static final String PARTY = "merchant-soap";
  public static final String NS = "urn:merchant:v1";
  public static final String WSSE =
      "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd";
  public static final String USERNAME = "bits";
  public static final String PASSWORD_PROPERTY = "sim.merchant-soap.ws-password";

  /** A seeded merchant of the merchant system. */
  public record Merchant(String id, boolean active, java.util.Set<String> tills) {}

  /** The seeded merchants (the orchestrator's dev config maps wallet 01911000020 to M-0001). */
  public static final Map<String, Merchant> MERCHANTS = Map.of(
      "M-0001", new Merchant("M-0001", true, java.util.Set.of("TILL-01", "TILL-02")),
      "M-0002", new Merchant("M-0002", false, java.util.Set.of("TILL-01")),
      "M-0003", new Merchant("M-0003", true, java.util.Set.of()));

  record Notify(String reference, String merchantId, long amountMinor, String notifyRef) {}

  private final MockControl control;
  private final Environment environment;
  private final Map<String, Notify> notifies = new ConcurrentHashMap<>();
  private final Map<String, String> refunds = new ConcurrentHashMap<>();
  private final Map<String, Long> refunded = new ConcurrentHashMap<>();
  private final AtomicLong refs = new AtomicLong();

  public MerchantSoapMock(MockControl control, Environment environment) {
    this.control = control;
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
      case "ValidateMerchant" -> validate(text(op, "merchantId"), text(op, "tillId"));
      case "NotifyPayment" -> notify(text(op, "merchantId"), text(op, "amount"), text(op, "reference"));
      case "QueryNotify" -> query(text(op, "reference"));
      case "Refund" -> refund(text(op, "merchantId"), text(op, "amount"), text(op, "reference"), text(op, "refundId"));
      default -> fault("Client", "1000", "unknown operation");
    };
  }

  private SoapReply validate(String merchantId, String tillId) {
    Decision d = control.decide(PARTY, "validate", Map.of("merchantId", merchantId), SimMode.SUCCESS);
    return run(d, () -> {
      Merchant m = MERCHANTS.get(merchantId);
      if (m == null) {
        return fault("Client", "5001", "merchant not found");
      }
      if (!m.active()) {
        return fault("Client", "5002", "merchant inactive");
      }
      if (!tillId.isEmpty() && !m.tills().contains(tillId)) {
        return fault("Client", "5003", "invalid till");
      }
      return SoapXml.ok("<m:ValidateMerchantResponse xmlns:m=\"" + NS + "\">" + el("status", "ACTIVE")
          + el("merchantRef", "MREF-" + m.id()) + "</m:ValidateMerchantResponse>");
    }, "5001");
  }

  private SoapReply notify(String merchantId, String amount, String reference) {
    Decision d = control.decide(PARTY, "notify", Map.of("merchantId", merchantId, "txnRef", reference), SimMode.SUCCESS);
    return run(d, () -> {
      if (reference.isEmpty() || !MERCHANTS.containsKey(merchantId)) {
        return fault("Client", "5001", "merchant not found");
      }
      Notify n = notifies.computeIfAbsent(reference,
          r -> new Notify(r, merchantId, minor(amount), "NTF-" + refs.incrementAndGet()));
      log.atInfo()
          .addKeyValue("event", "sim.merchant_soap.notify")
          .addKeyValue("partnerId", PARTY)
          .log("payment notify {} received ({})", n.notifyRef(), d.source());
      return SoapXml.ok("<m:NotifyPaymentResponse xmlns:m=\"" + NS + "\">" + el("status", "RECEIVED")
          + el("notifyRef", n.notifyRef()) + el("code", "0000") + "</m:NotifyPaymentResponse>");
    }, "5001");
  }

  private SoapReply query(String reference) {
    Decision d = control.decide(PARTY, "query", Map.of("txnRef", reference), SimMode.SUCCESS);
    return run(d, () -> {
      Notify n = notifies.get(reference);
      return SoapXml.ok("<m:QueryNotifyResponse xmlns:m=\"" + NS + "\">" + el("status", n == null ? "NOT_FOUND" : "RECEIVED")
          + el("notifyRef", n == null ? "" : n.notifyRef()) + "</m:QueryNotifyResponse>");
    }, "5001");
  }

  private SoapReply refund(String merchantId, String amount, String reference, String refundId) {
    Decision d = control.decide(PARTY, "refund", Map.of("merchantId", merchantId, "txnRef", reference), SimMode.SUCCESS);
    return run(d, () -> {
      String key = refundId.isEmpty() ? reference : refundId;
      String done = refunds.get(key);
      if (done != null) {
        return refunded(done);
      }
      Notify n = notifies.get(reference);
      long value = minor(amount);
      if (n == null || value <= 0 || refunded.getOrDefault(reference, 0L) + value > n.amountMinor()) {
        return fault("Client", "5004", "refund refused");
      }
      refunded.merge(reference, value, Long::sum);
      String ref = refunds.computeIfAbsent(key, k -> "RF-" + refs.incrementAndGet());
      return refunded(ref);
    }, "5004");
  }

  private static SoapReply refunded(String ref) {
    return SoapXml.ok("<m:RefundResponse xmlns:m=\"" + NS + "\">" + el("status", "REFUNDED")
        + el("refundRef", ref) + el("code", "0000") + "</m:RefundResponse>");
  }

  /** The mock-control decision around the call's own answer {@code apply}. */
  private static SoapReply run(Decision d, java.util.function.Supplier<SoapReply> apply, String defaultCode) {
    Optional<SimReply> closed = MockCalls.admit(d, PARTY, () -> new SimReply(503, "maintenance"));
    if (closed.isPresent()) {
      return fault("Server", "5030", "Service unavailable");
    }
    if (d.mode() == SimMode.BUSINESS_FAILURE) {
      return fault("Client", d.codeOr(defaultCode), "refused");
    }
    SoapReply reply = apply.get();
    return switch (d.mode()) {
      case TIMEOUT -> {
        MockCalls.pause(d.holdMs());
        yield reply;
      }
      case SERVER_ERROR_AFTER_SEND -> fault("Server", "5000", "Internal error");
      case MALFORMED ->
          new SoapReply(200, "<soap:Envelope xmlns:soap=\"" + SoapXml.ENVELOPE + "\"><soap:Body><m:Res");
      case CONNECTION_RESET -> throw new MockAbortException(PARTY);
      default -> reply;
    };
  }

  @Override
  public void reset(String party) {
    if (PARTY.equals(party)) {
      notifies.clear();
      refunds.clear();
      refunded.clear();
    }
  }

  /** The notifies received so far (for tests and the API suite's checks through the journal). */
  public int notifyCount() {
    return notifies.size();
  }

  private static long minor(String amount) {
    try {
      return new java.math.BigDecimal(amount).movePointRight(2).longValueExact();
    } catch (RuntimeException e) {
      return 0;
    }
  }

  private boolean authenticated(Element header) {
    String expected = environment.getProperty(PASSWORD_PROPERTY);
    if (expected == null || expected.isEmpty()) {
      return false;
    }
    Element security = header == null ? null : first(header, "Security");
    Element token = security == null ? null : first(security, "UsernameToken");
    Element user = token == null ? null : first(token, "Username");
    Element pass = token == null ? null : first(token, "Password");
    if (user == null || pass == null) {
      return false;
    }
    String type = pass.getAttribute("Type");
    if (!type.isEmpty() && !type.endsWith("#PasswordText")) {
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
    return value == null ? "" : "<m:" + name + ">" + escape(value) + "</m:" + name + ">";
  }

  private static SoapReply fault(String faultCode, String code, String message) {
    return SoapXml.fault(faultCode, message, "<m:error xmlns:m=\"" + NS + "\"><m:code>" + escape(code)
        + "</m:code><m:message>" + escape(message) + "</m:message></m:error>");
  }
}
