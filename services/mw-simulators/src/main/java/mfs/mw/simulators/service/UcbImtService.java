package mfs.mw.simulators.service;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import mfs.mw.simulators.client.CallbackClient;
import mfs.mw.simulators.client.CallbackSigner;
import mfs.mw.simulators.dto.CallbackMode;
import mfs.mw.simulators.dto.SimMode;
import mfs.mw.simulators.dto.SimReply;
import mfs.mw.simulators.dto.UcbImtRequests;
import mfs.mw.simulators.exception.ErrorCodes;
import mfs.mw.simulators.exception.MwException;
import mfs.mw.simulators.service.mockcontrol.Decision;
import mfs.mw.simulators.service.mockcontrol.MockCalls;
import mfs.mw.simulators.service.mockcontrol.MockControl;
import mfs.mw.simulators.service.mockcontrol.PartyState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * UCB IMT (ticket 119, FRS UC-16, MCK-06; mock party {@code ucb-imt}). {@code POST
 * /mock-control/ucb-imt/trigger} pushes a signed credit to mw-adapter-remit ({@code
 * sim.ucb-imt.credit-url}); a duplicate scenario sends that body twice and a late one waits first.
 * The adapter's later confirmation and status inquiry are {@code /ucb-imt/v1/remittances/{reference}}.
 * The beneficiary MSISDN and the sender's name are the credit body only: they are never logged.
 */
@Service
public class UcbImtService implements PartyState {

  public static final String PARTNER = "ucb-imt";

  private static final Logger log = LoggerFactory.getLogger(UcbImtService.class);

  private final MockControl control;
  private final CallbackClient client;
  private final CallbackSigner signer;
  private final JsonMapper json;
  private final String creditUrl;
  private final Map<String, Confirmed> confirmed = new ConcurrentHashMap<>();

  public UcbImtService(
      MockControl control,
      CallbackClient client,
      CallbackSigner signer,
      JsonMapper json,
      @Value("${sim.ucb-imt.credit-url:http://mw-adapter-remit:8080/remit/v1/credits}") String creditUrl) {
    this.control = control;
    this.client = client;
    this.signer = signer;
    this.json = json;
    this.creditUrl = creditUrl;
  }

  /** Pushes one credit (or two, or none) and answers what the middleware said. */
  public UcbImtRequests.TriggerResult trigger(UcbImtRequests.Trigger request) {
    Decision d = control.decide(PARTNER, "trigger", Map.of("msisdn", request.msisdn()), SimMode.SUCCESS);
    Optional<SimReply> closed =
        MockCalls.admit(d, PARTNER, () -> new SimReply(503, refusal("UNAVAILABLE", "5030", "maintenance")));
    if (closed.isPresent()) {
      throw new MwException(ErrorCodes.VAL_INVALID, "The remittance partner is in maintenance")
          .withStatus(HttpStatus.SERVICE_UNAVAILABLE);
    }
    if (d.mode() == SimMode.BUSINESS_FAILURE) {
      throw new MwException(ErrorCodes.VAL_INVALID, "The remittance was refused")
          .withStatus(HttpStatus.UNPROCESSABLE_CONTENT);
    }
    if (d.callback() == CallbackMode.NONE) {
      return new UcbImtRequests.TriggerResult(null, 0, -1);
    }
    String partnerRef = "IMT-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    String body =
        json.writeValueAsString(
            Map.of(
                "partnerRef", partnerRef,
                "beneficiaryMsisdn", request.msisdn(),
                "amount", request.amount(),
                "currency", "BDT",
                "senderName", request.sender(),
                "senderCountry", request.country()));
    int copies = d.callback() == CallbackMode.DUPLICATE ? 2 : 1;
    if (d.callback() == CallbackMode.LATE) {
      MockCalls.pause(d.callbackDelayMs());
    }
    int status = -1;
    for (int i = 0; i < copies; i++) {
      Map<String, String> headers = new HashMap<>(signer.headers(PARTNER, creditUrl, body));
      headers.put("X-Partner-Id", PARTNER);
      status = client.post(creditUrl, body, headers);
    }
    log.atInfo()
        .addKeyValue("event", "sim.ucb-imt.credit")
        .addKeyValue("partnerId", PARTNER)
        .log("pushed remittance {} ({} {})", partnerRef, copies, status);
    return new UcbImtRequests.TriggerResult(partnerRef, copies, status);
  }

  /** The adapter's CONFIRM_CREDIT. Unsigned requests are refused; a repeat keeps the first outcome. */
  public SimReply confirm(String reference, UcbImtRequests.Confirmation body, boolean signed) {
    Decision d = control.decide(PARTNER, "confirm", Map.of("partnerRef", reference), SimMode.SUCCESS);
    return MockCalls.answer(
        d,
        PARTNER,
        () -> store(reference, body, signed),
        () -> new SimReply(503, refusal("UNAVAILABLE", "5030", "maintenance")),
        () -> new SimReply(500, refusal("ERROR", "5000", "internal error")),
        code -> new SimReply(422, refusal("FAILED", code == null ? "4001" : code, "refused")));
  }

  /** The adapter's STATUS_QUERY: confirmed once {@link #confirm} stored this reference. */
  public SimReply status(String reference, boolean signed) {
    Decision d = control.decide(PARTNER, "status", Map.of("partnerRef", reference), SimMode.SUCCESS);
    return MockCalls.answer(
        d,
        PARTNER,
        () -> read(reference, signed),
        () -> new SimReply(503, refusal("UNAVAILABLE", "5030", "maintenance")),
        () -> new SimReply(500, refusal("ERROR", "5000", "internal error")),
        code -> new SimReply(422, refusal("FAILED", code == null ? "4001" : code, "refused")));
  }

  @Override
  public void reset(String party) {
    if (PARTNER.equals(party)) {
      confirmed.clear();
    }
  }

  private SimReply store(String reference, UcbImtRequests.Confirmation body, boolean signed) {
    if (!signed) {
      return new SimReply(401, refusal("UNAUTHENTICATED", "1002", "missing signature"));
    }
    if (!reference.equals(body.partnerRef())) {
      return new SimReply(400, refusal("INVALID", "1000", "partnerRef does not match the path"));
    }
    confirmed.putIfAbsent(reference, new Confirmed(body.upayRef(), body.outcome()));
    Confirmed stored = confirmed.get(reference);
    return new SimReply(200, Map.of("partnerRef", reference, "upayRef", stored.upayRef(), "outcome", stored.outcome()));
  }

  private SimReply read(String reference, boolean signed) {
    if (!signed) {
      return new SimReply(401, refusal("UNAUTHENTICATED", "1002", "missing signature"));
    }
    Confirmed stored = confirmed.get(reference);
    if (stored == null) {
      return new SimReply(404, refusal("NOT_FOUND", "4001", "unknown remittance"));
    }
    return new SimReply(
        200,
        Map.of(
            "partnerRef", reference,
            "confirmed", true,
            "upayRef", stored.upayRef(),
            "outcome", stored.outcome()));
  }

  private static Map<String, String> refusal(String status, String code, String message) {
    return Map.of("status", status, "code", code, "message", message);
  }

  private record Confirmed(String upayRef, String outcome) {}
}
