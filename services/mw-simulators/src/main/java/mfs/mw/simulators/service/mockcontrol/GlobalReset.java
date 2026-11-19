package mfs.mw.simulators.service.mockcontrol;

import java.util.ArrayList;
import java.util.List;
import mfs.mw.simulators.client.CoreMockControlClient;
import mfs.mw.simulators.dto.MockParty;
import mfs.mw.simulators.dto.ResetResult;
import mfs.mw.simulators.dto.ResetResult.PartyResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * {@code POST /mock-control/reset} (FRS MCT-07, ticket 103): one call resets every mock. Each local party
 * of {@link MockParties#ALL} is reset as {@code DELETE /mock-control/{party}/journal} resets it ({@link
 * PartyReset}: journal, scenarios, switch, settings, kept state), then mw-core-mock's own reset (ledger
 * back to the seed, its scenarios and journal cleared) through {@link CoreMockControlClient}. Answers one
 * line per party; one party failing does not stop the others.
 */
@Service
@Profile(MockControl.PROFILE)
public class GlobalReset {

  private static final Logger log = LoggerFactory.getLogger(GlobalReset.class);

  private final PartyReset parties;
  private final CoreMockControlClient core;

  public GlobalReset(PartyReset parties, CoreMockControlClient core) {
    this.parties = parties;
    this.core = core;
  }

  /** Resets every mock; {@code seedVersion} goes to mw-core-mock (null = the version in force there). */
  public ResetResult all(String seedVersion) {
    List<PartyResult> out = new ArrayList<>();
    for (MockParty p : MockParties.ALL) {
      try {
        parties.reset(p.name());
        out.add(PartyResult.reset(p.name()));
      } catch (RuntimeException e) {
        out.add(PartyResult.failed(p.name(), e.getClass().getSimpleName()));
      }
    }
    out.addAll(core.reset(seedVersion));
    log.atInfo().addKeyValue("event", "sim.mock.reset.all")
        .addKeyValue("failed", out.stream().filter(r -> !"RESET".equals(r.status())).count())
        .log("every mock party reset");
    return new ResetResult(out);
  }
}
