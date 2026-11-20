package mfs.mw.simulators.service;

import java.util.List;
import java.util.Map;
import mfs.mw.simulators.dto.NpsbParticipant;
import mfs.mw.simulators.dto.SimMode;
import mfs.mw.simulators.dto.SimReply;
import mfs.mw.simulators.service.mockcontrol.Decision;
import mfs.mw.simulators.service.mockcontrol.MockCalls;
import mfs.mw.simulators.service.mockcontrol.MockControl;
import org.springframework.stereotype.Service;

/**
 * NPSB switch stand-in (ticket 121a, FRS EXT-13, MCK-01; mock party {@code npsb}). For now the participant
 * list of the versioned seed {@code seed/v1/npsb/participants.json} ({@code GET /npsb/v1/participants}), the
 * list the app gateway's beneficiary lookup reads. The ISO 20022 transfers (pacs.008 / pacs.002), status
 * (pacs.028), return (camt.056) and the inbound push come with ticket 121b.
 */
@Service
public class NpsbService {

  public static final String PARTNER = "npsb";

  static final List<NpsbParticipant> PARTICIPANTS = SeedFiles.npsbParticipants(SeedFiles.VERSION);

  private final MockControl control;

  public NpsbService(MockControl control) {
    this.control = control;
  }

  /** The participants, as mock control decides (a BUSINESS_ERROR refuses with its code). */
  public SimReply participants() {
    Decision d = control.decide(PARTNER, "participants", Map.of(), SimMode.SUCCESS);
    return MockCalls.answer(
        d,
        PARTNER,
        () -> new SimReply(200, Map.of("participants", PARTICIPANTS)),
        () -> new SimReply(503, Map.of("status", "ERROR")),
        () -> new SimReply(500, Map.of("status", "ERROR")),
        code -> new SimReply(422, Map.of("status", "FAILED", "code", code == null ? "NARR" : code)));
  }
}
