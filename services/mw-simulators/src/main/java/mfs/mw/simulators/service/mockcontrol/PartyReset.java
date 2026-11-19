package mfs.mw.simulators.service.mockcontrol;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * {@code DELETE /mock-control/{party}/journal} (FRS MCT-06): the party's journal, scenarios, switch,
 * settings and kept state ({@link PartyState}: ledgers, reversals, received IPNs) back to a fresh start.
 */
@Service
@Profile(MockControl.PROFILE)
public class PartyReset {

  private static final Logger log = LoggerFactory.getLogger(PartyReset.class);

  private final LegacyScenarios legacy;
  private final Journal journal;
  private final List<PartyState> states;

  public PartyReset(LegacyScenarios legacy, Journal journal, List<PartyState> states) {
    this.legacy = legacy;
    this.journal = journal;
    this.states = states;
  }

  public void reset(String party) {
    MockParties.require(party);
    legacy.forget(party);
    journal.clear(party);
    states.forEach(s -> s.reset(party));
    log.atInfo().addKeyValue("event", "sim.mock.party.reset").addKeyValue("partnerId", party).log("party {} reset", party);
  }
}
