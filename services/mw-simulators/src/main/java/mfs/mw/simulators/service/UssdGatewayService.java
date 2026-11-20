package mfs.mw.simulators.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import mfs.mw.simulators.client.UssdGatewayClient;
import mfs.mw.simulators.client.UssdSmppGateway;
import mfs.mw.simulators.dto.SimMode;
import mfs.mw.simulators.dto.UssdDialRequest;
import mfs.mw.simulators.dto.UssdDialResult;
import mfs.mw.simulators.dto.UssdScreen;
import mfs.mw.simulators.dto.UssdSmppBinds;
import mfs.mw.simulators.service.mockcontrol.Decision;
import mfs.mw.simulators.service.mockcontrol.MockCalls;
import mfs.mw.simulators.service.mockcontrol.MockControl;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * USSD gateway simulator in HTTP mode (spec 22.2, ticket 26): plays an MNO that POSTs every step
 * of a session to mw-ussd ({@code sim.ussd-gateway.target-url} + mno, through APISIX in the
 * stack). BEGIN with the service code, then one CONTINUE per input, until mw-ussd ends the
 * session or the inputs run out (then the session is left open, like a subscriber who stopped
 * typing, or ended with END when {@code hangUp}). With {@code resume} (and a {@code sessionId}) it
 * continues a session left open by an earlier call: no BEGIN, one CONTINUE per input. An MNO in {@code sim.ussd-gateway.smpp.mnos}
 * is played over SMPP instead ({@link UssdSmppGateway}, ticket 27): same request, same result.
 *
 * <p>Each play is operation {@code dial} of the mock party {@code ussdgw} (ticket 102b, ADR D33; keys
 * {@code msisdn}, {@code mno}): DELAY waits before the first step; CONNECTION_RESET hangs up after the
 * first screen (no further step, the session left open); a DOWN switch sends nothing (one screen with
 * status -1), MAINTENANCE nothing either (status 503).
 */
@Service
public class UssdGatewayService {

  private final UssdGatewayClient client;
  private final UssdSmppGateway smpp;
  private final MockControl control;
  private final String targetUrl;
  private final String endFlag;

  public UssdGatewayService(
      UssdGatewayClient client,
      UssdSmppGateway smpp,
      MockControl control,
      @Value("${sim.ussd-gateway.target-url:http://apisix:9080/ussd/}") String targetUrl,
      @Value("${sim.ussd-gateway.end-flag:END}") String endFlag) {
    this.client = client;
    this.smpp = smpp;
    this.control = control;
    this.targetUrl = targetUrl.endsWith("/") ? targetUrl : targetUrl + "/";
    this.endFlag = endFlag;
  }

  public UssdDialResult dial(UssdDialRequest asked) {
    Decision d = control.decide(UssdSmppGateway.PARTY, "dial", Map.of("msisdn", asked.msisdn(), "mno", asked.mno()),
        SimMode.SUCCESS);
    MockCalls.pause(d.delayMs());
    if (d.gate() != Decision.Gate.OPEN) {
      int status = d.gate() == Decision.Gate.DOWN ? -1 : 503;
      String session = asked.sessionId() == null ? "sim-" + UUID.randomUUID() : asked.sessionId();
      return new UssdDialResult(session, List.of(new UssdScreen(status, null, null)), false);
    }
    UssdDialRequest req = d.mode() == SimMode.CONNECTION_RESET
        ? new UssdDialRequest(asked.mno(), asked.msisdn(), asked.serviceCode(), asked.sessionId(), List.of(), asked.resume())
        : asked;
    if (smpp.plays(req.mno())) {
      return smpp.play(req);
    }
    String session = req.sessionId() == null || req.sessionId().isBlank() ? "sim-" + UUID.randomUUID() : req.sessionId();
    String url = targetUrl + req.mno();
    List<UssdScreen> screens = new ArrayList<>();
    boolean resume = Boolean.TRUE.equals(req.resume()) && req.sessionId() != null && !req.sessionId().isBlank() && !req.inputs().isEmpty();
    UssdScreen s = resume ? null : client.step(req.mno(), url, session, req.msisdn(), req.serviceCode(), "BEGIN", "");
    if (s != null) {
      screens.add(s);
    }
    for (String input : req.inputs()) {
      if (s != null && (s.status() != 200 || endFlag.equals(s.flag()))) {
        break;
      }
      s = client.step(req.mno(), url, session, req.msisdn(), req.serviceCode(), "CONTINUE", input);
      screens.add(s);
    }
    if (s == null) {
      return new UssdDialResult(session, List.of(), false);
    }
    return new UssdDialResult(session, List.copyOf(screens), s.status() == 200 && endFlag.equals(s.flag()));
  }

  /** SMPP mode: closes every bind (mw-ussd reconnects). */
  public void dropSmppBinds() {
    smpp.drop();
  }

  public UssdSmppBinds smppBinds() {
    return smpp.binds();
  }
}
