package mfs.mw.core.mock.service.mockcontrol;

import mfs.mw.core.mock.dto.ResetResult;
import mfs.mw.core.mock.service.WalletSeeder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * {@code DELETE /mock-control/core/journal} (MCT-06) and {@code POST /mock-control/reset} (MCT-07,
 * ticket 103). The party reset clears the core's scenarios, switch, settings, old faults and journal;
 * the global reset also brings the ledger back to the seed ({@link WalletSeeder#restore}: holds,
 * entries, command log, REST request hashes and used assertions gone, the seed wallets and sheets
 * reloaded from {@code seed/<version>/}, every other wallet back to its opening balance).
 */
@Service
@Profile(MockControl.PROFILE)
public class CoreReset {

  private static final Logger log = LoggerFactory.getLogger(CoreReset.class);

  private final ScenarioEngine engine;
  private final PartySwitch switches;
  private final Journal journal;
  private final LegacyFaults faults;
  private final WalletSeeder seeder;

  public CoreReset(ScenarioEngine engine, PartySwitch switches, Journal journal, LegacyFaults faults, WalletSeeder seeder) {
    this.engine = engine;
    this.switches = switches;
    this.journal = journal;
    this.faults = faults;
    this.seeder = seeder;
  }

  /** The party's control state and journal back to a fresh start (the ledger stays). */
  public void party(String party) {
    MockParties.require(party);
    engine.clear(party);
    faults.forget();
    switches.reset(party);
    journal.clear(party);
    log.atInfo().addKeyValue("event", "coremock.mock.party.reset").addKeyValue("partnerId", party)
        .log("party {} reset", party);
  }

  /**
   * The global reset: the party reset, then the ledger back to the seed of {@code seedVersion} (null =
   * the version in force).
   */
  public ResetResult all(String seedVersion) {
    String version = seedVersion == null || seedVersion.isBlank() ? seeder.version() : seedVersion;
    seeder.restore(version);
    party(MockControl.CORE);
    return new ResetResult(java.util.List.of(ResetResult.PartyResult.reset(MockControl.CORE, version)));
  }
}
