package mfs.mw.simulators.service.mockcontrol;

import java.util.Map;
import mfs.mw.simulators.config.SimulatorProperties;
import mfs.mw.simulators.dto.CallbackMode;
import mfs.mw.simulators.dto.MockSettings;
import mfs.mw.simulators.dto.SimMode;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Without the mock-control profile: every call follows its magic value; no callbacks, no switches. */
@Service
@Profile("!" + MockControl.PROFILE)
public class MagicValuesOnly implements MockControl {

  private final SimulatorProperties props;

  public MagicValuesOnly(SimulatorProperties props) {
    this.props = props;
  }

  @Override
  public Decision decide(String party, String operation, Map<String, String> keys, SimMode magic) {
    MockParties.require(party);
    Decision d = Decision.magic(magic, props.timeoutDelay().toMillis());
    // a callback on the caller's own connection (the SMSC's DLR) goes once, as without mock control
    return MockParties.callsBackInBand(party)
        ? new Decision(d.gate(), d.mode(), null, null, d.holdMs(), 0, CallbackMode.ONCE, 0, d.source())
        : d;
  }

  @Override
  public MockSettings settings(String party) {
    return MockSettings.DEFAULT;
  }
}
