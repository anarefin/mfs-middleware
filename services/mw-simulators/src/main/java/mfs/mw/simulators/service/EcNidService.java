package mfs.mw.simulators.service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import mfs.mw.simulators.dto.SimMode;
import mfs.mw.simulators.dto.SimReply;
import mfs.mw.simulators.service.mockcontrol.Decision;
import mfs.mw.simulators.service.mockcontrol.MockCalls;
import mfs.mw.simulators.service.mockcontrol.MockControl;
import mfs.mw.simulators.service.mockcontrol.PartyState;
import mfs.mw.simulators.util.SoapXml;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import tools.jackson.databind.JsonNode;

/**
 * The Election Commission NID service (ticket 124b, FRS EXT-07; mock party {@code ec}; contract {@code
 * api/mw-simulators/ec-nid.v1.openapi.yaml}). REST {@code POST /ec/v1/nid/verify {nid, dob, consentId, requestId}}
 * and SOAP {@code POST /ec/v1/soap} ({@code urn:ec:nid:v1} VerifyNid) answer the citizen of {@code
 * seed/v1/ec/nid.json}, HTTP Basic required (any credentials: the mock checks presence). Magic values on the NID:
 * ending {@code 000} not found, {@code 999} the answer held past the caller's timeout (TIMEOUT), {@code 111} the
 * record with a date of birth a year after the one asked (DOB mismatch). Mock control decides first; nothing is
 * logged (the records are fake but shaped like PII).
 */
@Service
public class EcNidService implements PartyState {

  public static final String PARTNER = "ec";
  public static final String SOAP_NS = "urn:ec:nid:v1";
  static final String NOT_FOUND = "NID_NOT_FOUND";

  private final MockControl control;
  private final Map<String, JsonNode> citizens = new LinkedHashMap<>();

  public EcNidService(MockControl control) {
    this.control = control;
    SeedFiles.ecCitizens(SeedFiles.VERSION).forEach(c -> citizens.put(c.path("nid").asString(), c));
  }

  /** The REST verify: 200 the record, 404 {@code NID_NOT_FOUND}, 401 without credentials. */
  public SimReply verify(String nid, String dob, boolean authenticated) {
    if (!authenticated) {
      return new SimReply(401, refusal("UNAUTHENTICATED", "missing credentials"));
    }
    Decision d = decide(nid);
    return MockCalls.answer(d, PARTNER, () -> lookup(nid, dob),
        () -> new SimReply(503, refusal("UNAVAILABLE", "maintenance")),
        () -> new SimReply(500, refusal("ERROR", "internal error")),
        code -> new SimReply(404, refusal(code == null ? NOT_FOUND : code, "refused")));
  }

  /** The SOAP verify: the same answer as {@link #verify}, as a VerifyNidResponse or a fault. */
  public SoapXml.SoapReply soap(String envelope, boolean authenticated) {
    if (!authenticated) {
      return SoapXml.fault("Client", "missing credentials", "<ec:code xmlns:ec=\"" + SOAP_NS + "\">UNAUTHENTICATED</ec:code>");
    }
    Element op;
    try {
      Document doc = SoapXml.parse(envelope);
      op = SoapXml.bodyElement(doc);
    } catch (IllegalArgumentException e) {
      return SoapXml.fault("Client", "unreadable request", "");
    }
    if (op == null || !"VerifyNid".equals(op.getLocalName())) {
      return SoapXml.fault("Client", "unknown operation", "");
    }
    SimReply reply = verify(SoapXml.text(op, SOAP_NS, "nid"), SoapXml.text(op, SOAP_NS, "dob"), true);
    if (reply.httpStatus() != 200 || !(reply.body() instanceof Map<?, ?> body)) {
      String code = reply.body() instanceof Map<?, ?> m && m.get("code") != null ? m.get("code").toString() : "ERROR";
      return SoapXml.fault(reply.httpStatus() >= 500 ? "Server" : "Client", "verify refused",
          "<ec:code xmlns:ec=\"" + SOAP_NS + "\">" + SoapXml.escape(code) + "</ec:code>");
    }
    StringBuilder xml = new StringBuilder("<ec:VerifyNidResponse xmlns:ec=\"" + SOAP_NS + "\">");
    body.forEach((k, v) -> xml.append("<ec:").append(k).append('>').append(SoapXml.escape(String.valueOf(v)))
        .append("</ec:").append(k).append('>'));
    return SoapXml.ok(xml.append("</ec:VerifyNidResponse>").toString());
  }

  @Override
  public void reset(String party) {
    // nothing kept: every answer comes from the seed and the decision
  }

  private Decision decide(String nid) {
    String n = nid == null ? "" : nid;
    SimMode magic = n.endsWith("999") ? SimMode.TIMEOUT : SimMode.SUCCESS;
    return control.decide(PARTNER, "verify", Map.of("nid", n), magic);
  }

  private SimReply lookup(String nid, String dob) {
    JsonNode c = nid == null || nid.endsWith("000") ? null : citizens.get(nid);
    if (c == null) {
      return new SimReply(404, refusal(NOT_FOUND, "no such NID"));
    }
    Map<String, Object> body = new LinkedHashMap<>();
    for (String f : new String[] {"nid", "dob", "nameEn", "nameBn", "fatherName", "motherName", "address", "photo"}) {
      body.put(f, c.path(f).asString());
    }
    if (nid.endsWith("111")) {
      body.put("dob", shifted(dob, c.path("dob").asString()));
    }
    return new SimReply(200, body);
  }

  private static String shifted(String asked, String seeded) {
    try {
      return LocalDate.parse(asked).plusYears(1).toString();
    } catch (DateTimeParseException | NullPointerException e) {
      return LocalDate.parse(seeded).plusYears(1).toString();
    }
  }

  private static Map<String, String> refusal(String code, String message) {
    return Map.of("code", code, "message", message);
  }
}
