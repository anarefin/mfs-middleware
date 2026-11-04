package mfs.mw.simulators.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import mfs.mw.simulators.client.UssdGatewayClient;
import mfs.mw.simulators.client.UssdSmppGateway;
import mfs.mw.simulators.dto.UssdDialRequest;
import mfs.mw.simulators.dto.UssdDialResult;
import mfs.mw.simulators.dto.UssdScreen;
import mfs.mw.simulators.dto.UssdSmppBinds;
import mfs.mw.simulators.dto.UssdSmppScenario;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * USSD gateway simulator in HTTP mode (spec 22.2, ticket 26): plays an MNO that POSTs every step
 * of a session to mw-ussd ({@code sim.ussd-gateway.target-url} + mno, through APISIX in the
 * stack). BEGIN with the service code, then one CONTINUE per input, until mw-ussd ends the
 * session or the inputs run out (then the session is left open, like a subscriber who stopped
 * typing, or ended with END when {@code hangUp}). An MNO in {@code sim.ussd-gateway.smpp.mnos}
 * is played over SMPP instead ({@link UssdSmppGateway}, ticket 27): same request, same result.
 */
@Service
public class UssdGatewayService {

  private final UssdGatewayClient client;
  private final UssdSmppGateway smpp;
  private final String targetUrl;
  private final String endFlag;

  public UssdGatewayService(
      UssdGatewayClient client,
      UssdSmppGateway smpp,
      @Value("${sim.ussd-gateway.target-url:http://apisix:9080/ussd/}") String targetUrl,
      @Value("${sim.ussd-gateway.end-flag:END}") String endFlag) {
    this.client = client;
    this.smpp = smpp;
    this.targetUrl = targetUrl.endsWith("/") ? targetUrl : targetUrl + "/";
    this.endFlag = endFlag;
  }

  public UssdDialResult dial(UssdDialRequest req) {
    if (smpp.plays(req.mno())) {
      return smpp.play(req);
    }
    String session = req.sessionId() == null || req.sessionId().isBlank() ? "sim-" + UUID.randomUUID() : req.sessionId();
    String url = targetUrl + req.mno();
    List<UssdScreen> screens = new ArrayList<>();
    UssdScreen s = client.step(url, session, req.msisdn(), req.serviceCode(), "BEGIN", "");
    screens.add(s);
    for (String input : req.inputs()) {
      if (s.status() != 200 || endFlag.equals(s.flag())) {
        break;
      }
      s = client.step(url, session, req.msisdn(), req.serviceCode(), "CONTINUE", input);
      screens.add(s);
    }
    return new UssdDialResult(session, List.copyOf(screens), s.status() == 200 && endFlag.equals(s.flag()));
  }

  /** SMPP mode: replaces the gateway's misbehaviour scenario. */
  public void smppScenario(UssdSmppScenario scenario) {
    smpp.scenario(scenario);
  }

  /** SMPP mode: closes every bind (mw-ussd reconnects). */
  public void dropSmppBinds() {
    smpp.drop();
  }

  public UssdSmppBinds smppBinds() {
    return smpp.binds();
  }
}
