package mfs.mw.simulators.service;

import java.util.Map;
import mfs.mw.simulators.dto.SimMode;
import mfs.mw.simulators.dto.SimReply;
import mfs.mw.simulators.service.mockcontrol.Decision;
import mfs.mw.simulators.service.mockcontrol.MockCalls;
import mfs.mw.simulators.service.mockcontrol.MockControl;
import mfs.mw.simulators.service.mockcontrol.PartyState;
import org.springframework.stereotype.Service;

/**
 * UCB ATM switch stand-in (ticket 120a, FRS EXT-16; mock party {@code ucbatm}). The switch calls
 * mw-adapter-atm over SOAP; this mock is the outbound dispense-status inquiry the adapter makes
 * after a missing ConfirmDispense ({@code GET /ucbatm/v1/dispenses/{reference}}). A known reference
 * is DISPENSED unless mock control says otherwise: business code {@code 5404} is NOT_FOUND, any
 * other business code is NOT_DISPENSED.
 */
@Service
public class UcbAtmService implements PartyState {

  public static final String PARTNER = "ucbatm";
  public static final String NOT_FOUND = "5404";

  private final MockControl control;

  public UcbAtmService(MockControl control) {
    this.control = control;
  }

  /** STATUS_QUERY of a cash-out by our stable reference. */
  public SimReply dispenseStatus(String reference) {
    Decision d = control.decide(PARTNER, "status", Map.of("txnRef", reference), SimMode.SUCCESS);
    return MockCalls.answer(
        d,
        PARTNER,
        () -> new SimReply(200, Map.of("status", "DISPENSED", "dispenseRef", "dsp-1", "code", "00")),
        () -> new SimReply(503, Map.of("status", "ERROR")),
        () -> new SimReply(500, Map.of("status", "ERROR")),
        code -> NOT_FOUND.equals(code)
            ? new SimReply(404, Map.of("status", "NOT_FOUND", "code", NOT_FOUND))
            : new SimReply(200, Map.of(
                "status", "NOT_DISPENSED", "code", code == null ? "5101" : code, "message", "jam")));
  }

  @Override
  public void reset(String party) {
    if (!PARTNER.equals(party)) {
      return;
    }
    // nothing kept: each inquiry answers from the decision, not a ledger
  }
}
